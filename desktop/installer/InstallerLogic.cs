using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.Drawing;
using System.IO;
using System.IO.Compression;
using System.Linq;
using System.Reflection;
using System.Threading;
using System.Threading.Tasks;
using Microsoft.Win32;

namespace Installer
{
    public static class InstallerLogic
    {
        private const string DisplayName = "Nuvio";
        private const string Publisher = "Nuvio";
        private const string UninstallKeyPath = @"Software\Microsoft\Windows\CurrentVersion\Uninstall\Nuvio";
        private const string InstallLocationKeyPath = @"Software\Nuvio\Nuvio";
        private const string LauncherArguments = "--processStart Nuvio.exe";
        private const string UninstallerName = "Update.exe";

        public const string ExecutableFileName = "Nuvio.exe";

        private static readonly string[] ManagedProcessNames = { "Nuvio" };
        private static readonly string[] ShortcutFileNames = { "Nuvio.lnk", @"Nuvio\Nuvio.lnk" };

        public static async Task RunInstallationAsync()
        {
            await Task.Run(() =>
            {
                InstallRootLayout layout = InstallRootLayout.FromLocalAppData(GetLocalAppDataDirectory());
                InstallerLogger.Info("Installing Nuvio to " + layout.RootPath);

                WaitForProcessesToExit(layout, TimeSpan.FromSeconds(30));
                DeleteShortcuts();
                Directory.CreateDirectory(layout.RootPath);
                Directory.CreateDirectory(layout.StagingPath);

                string stagingPath = ActivationManager.PrepareStagingDirectory(layout, DisplayName);
                ExtractPayload(stagingPath);
                string payloadVersion = ResolvePayloadVersion(Path.Combine(stagingPath, ExecutableFileName));
                ActivationResult activation = ActivationManager.Activate(layout, stagingPath, payloadVersion);

                DeleteLegacyRootEntries(layout);
                CopyBootstrapperToUninstaller(layout.RootPath);
                ExtractInstallerIcon(layout.RootIconPath);
                CreateUninstallRegistryKeys(layout.RootPath, activation.ActiveExecutablePath, layout.UpdateExePath);
                CreateCompatibilityInstallLocationKey(layout.RootPath);
                CreateShortcuts(layout.RootPath, layout.UpdateExePath, layout.RootIconPath);
                LaunchApplication(layout.UpdateExePath, LauncherArguments, layout.RootPath);
                InstallerLogger.Info("Nuvio installation completed.");
            }).ConfigureAwait(false);
        }

        public static async Task RunUninstallationAsync()
        {
            await Task.Run(() =>
            {
                InstallRootLayout layout = InstallRootLayout.FromLocalAppData(GetLocalAppDataDirectory());
                InstallerLogger.Info("Uninstalling Nuvio from " + layout.RootPath);

                WaitForProcessesToExit(layout, TimeSpan.FromSeconds(30));
                DeleteShortcuts();
                DeleteRegistryKeyTree(Registry.CurrentUser, UninstallKeyPath);
                DeleteRegistryKeyTree(Registry.CurrentUser, InstallLocationKeyPath);
                ScheduleDirectoryDeletion(layout.RootPath);
                InstallerLogger.Info("Nuvio uninstall cleanup scheduled.");
            }).ConfigureAwait(false);
        }

        private static string GetLocalAppDataDirectory()
        {
            string path = Environment.GetFolderPath(Environment.SpecialFolder.LocalApplicationData);
            if (string.IsNullOrWhiteSpace(path)) throw new InvalidOperationException("LocalAppData is unavailable.");
            return path;
        }

        private static void WaitForProcessesToExit(InstallRootLayout layout, TimeSpan timeout)
        {
            Stopwatch stopwatch = Stopwatch.StartNew();
            while (stopwatch.Elapsed < timeout)
            {
                Process[] processes = ManagedProcessNames.SelectMany(Process.GetProcessesByName).ToArray();
                processes = processes.Concat(Process.GetProcesses()
                    .Where(process => process.Id != Process.GetCurrentProcess().Id)
                    .Where(process => ProcessMatchesPath(process, layout.GetInstalledExecutablePaths(ExecutableFileName))))
                    .GroupBy(process => process.Id)
                    .Select(group => group.First())
                    .ToArray();

                if (processes.Length == 0) return;
                if (stopwatch.Elapsed.TotalSeconds > 5)
                {
                    foreach (Process process in processes)
                    {
                        try { process.Kill(); } catch { }
                    }
                }

                foreach (Process process in processes) process.Dispose();
                Thread.Sleep(500);
            }

            InstallerLogger.Warn("Timed out waiting for Nuvio processes to exit.");
        }

        private static bool ProcessMatchesPath(Process process, IEnumerable<string> paths)
        {
            try
            {
                string processPath = process.MainModule?.FileName;
                return !string.IsNullOrWhiteSpace(processPath) && paths.Contains(
                    Path.GetFullPath(processPath),
                    StringComparer.OrdinalIgnoreCase);
            }
            catch
            {
                return false;
            }
        }

        private static void ExtractPayload(string destinationDirectory)
        {
            Assembly assembly = Assembly.GetExecutingAssembly();
            string resourceName = assembly.GetManifestResourceNames()
                .FirstOrDefault(name => name.EndsWith("payload.zip", StringComparison.OrdinalIgnoreCase));
            if (resourceName == null)
            {
                throw new InvalidOperationException("Embedded payload.zip was not found. Run the installer build script first.");
            }

            using (Stream stream = assembly.GetManifestResourceStream(resourceName))
            using (ZipArchive archive = new ZipArchive(stream, ZipArchiveMode.Read))
            {
                string destinationRoot = EnsureTrailingSeparator(Path.GetFullPath(destinationDirectory));
                foreach (ZipArchiveEntry entry in archive.Entries)
                {
                    string targetPath = Path.GetFullPath(Path.Combine(destinationRoot, entry.FullName));
                    if (!targetPath.StartsWith(destinationRoot, StringComparison.OrdinalIgnoreCase))
                    {
                        throw new InvalidDataException("Installer payload contained an invalid path.");
                    }

                    if (string.IsNullOrEmpty(entry.Name))
                    {
                        Directory.CreateDirectory(targetPath);
                        continue;
                    }

                    Directory.CreateDirectory(Path.GetDirectoryName(targetPath));
                    ExtractEntryWithRetries(entry, targetPath);
                }
            }
        }

        private static void ExtractEntryWithRetries(ZipArchiveEntry entry, string targetPath)
        {
            for (int attempt = 1; attempt <= 5; attempt++)
            {
                try
                {
                    entry.ExtractToFile(targetPath, true);
                    return;
                }
                catch (IOException exception) when (attempt < 5)
                {
                    InstallerLogger.Warn("Retrying payload extraction for " + entry.FullName + ": " + exception.Message);
                    Thread.Sleep(500);
                }
                catch (UnauthorizedAccessException exception) when (attempt < 5)
                {
                    InstallerLogger.Warn("Retrying payload extraction for " + entry.FullName + ": " + exception.Message);
                    Thread.Sleep(500);
                }
            }

            throw new IOException("Could not extract payload entry " + entry.FullName);
        }

        private static string ResolvePayloadVersion(string executablePath)
        {
            FileVersionInfo versionInfo = FileVersionInfo.GetVersionInfo(executablePath);
            string version = versionInfo.ProductVersion;
            if (string.IsNullOrWhiteSpace(version) || version == "0.0.0.0")
            {
                version = Assembly.GetExecutingAssembly().GetName().Version?.ToString(3);
            }

            return InstallRootLayout.NormalizeVersion(version);
        }

        private static void CopyBootstrapperToUninstaller(string installRoot)
        {
            string sourcePath = GetCurrentProcessPath();
            string destinationPath = Path.Combine(installRoot, UninstallerName);
            if (PathsEqual(sourcePath, destinationPath)) return;

            string temporaryPath = destinationPath + ".tmp";
            File.Copy(sourcePath, temporaryPath, true);
            if (File.Exists(destinationPath)) File.Delete(destinationPath);
            File.Move(temporaryPath, destinationPath);
        }

        private static void ExtractInstallerIcon(string destinationPath)
        {
            using (Icon icon = Icon.ExtractAssociatedIcon(GetCurrentProcessPath()))
            using (FileStream stream = File.Create(destinationPath))
            {
                if (icon == null) throw new InvalidOperationException("Nuvio installer icon could not be extracted.");
                icon.Save(stream);
            }
        }

        private static void CreateUninstallRegistryKeys(string installRoot, string executablePath, string uninstallerPath)
        {
            FileVersionInfo versionInfo = FileVersionInfo.GetVersionInfo(executablePath);
            string uninstallCommand = "\"" + uninstallerPath + "\" --uninstall";
            using (RegistryKey key = Registry.CurrentUser.CreateSubKey(UninstallKeyPath))
            {
                key.SetValue("DisplayName", DisplayName);
                key.SetValue("DisplayIcon", executablePath);
                key.SetValue("DisplayVersion", versionInfo.ProductVersion ?? "0.0.0");
                key.SetValue("Publisher", string.IsNullOrWhiteSpace(versionInfo.CompanyName) ? Publisher : versionInfo.CompanyName);
                key.SetValue("InstallLocation", installRoot);
                key.SetValue("UninstallString", uninstallCommand);
                key.SetValue("QuietUninstallString", uninstallCommand + " /S");
                key.SetValue("InstallDate", DateTime.UtcNow.ToString("yyyyMMdd"));
                key.SetValue("NoModify", 1, RegistryValueKind.DWord);
                key.SetValue("NoRepair", 1, RegistryValueKind.DWord);
            }
        }

        private static void CreateCompatibilityInstallLocationKey(string installRoot)
        {
            using (RegistryKey key = Registry.CurrentUser.CreateSubKey(InstallLocationKeyPath))
            {
                key.SetValue(string.Empty, installRoot, RegistryValueKind.String);
            }
        }

        private static void CreateShortcuts(string installRoot, string launcherPath, string iconPath)
        {
            DeleteShortcuts();
            Type shellType = Type.GetTypeFromProgID("WScript.Shell");
            if (shellType == null) throw new InvalidOperationException("Windows shortcut support is unavailable.");

            dynamic shell = Activator.CreateInstance(shellType);
            CreateShortcut(shell, Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.DesktopDirectory), "Nuvio.lnk"), installRoot, launcherPath, iconPath);
            CreateShortcut(shell, Path.Combine(Environment.GetFolderPath(Environment.SpecialFolder.Programs), "Nuvio.lnk"), installRoot, launcherPath, iconPath);
        }

        private static void CreateShortcut(dynamic shell, string path, string workingDirectory, string targetPath, string iconPath)
        {
            dynamic shortcut = shell.CreateShortcut(path);
            shortcut.TargetPath = targetPath;
            shortcut.Arguments = LauncherArguments;
            shortcut.WorkingDirectory = workingDirectory;
            shortcut.IconLocation = iconPath + ",0";
            shortcut.Description = DisplayName;
            shortcut.Save();
        }

        private static void DeleteShortcuts()
        {
            string desktop = Environment.GetFolderPath(Environment.SpecialFolder.DesktopDirectory);
            string programs = Environment.GetFolderPath(Environment.SpecialFolder.Programs);
            foreach (string shortcutName in ShortcutFileNames)
            {
                DeleteFileIfExists(Path.Combine(desktop, shortcutName));
                DeleteFileIfExists(Path.Combine(programs, shortcutName));
            }
        }

        private static void DeleteLegacyRootEntries(InstallRootLayout layout)
        {
            HashSet<string> preserved = new HashSet<string>(layout.GetPreservedRootEntryNames(), StringComparer.OrdinalIgnoreCase);
            if (!Directory.Exists(layout.RootPath)) return;

            foreach (string filePath in Directory.GetFiles(layout.RootPath))
            {
                if (!preserved.Contains(Path.GetFileName(filePath))) DeleteFileIfExists(filePath);
            }

            foreach (string directoryPath in Directory.GetDirectories(layout.RootPath))
            {
                string name = Path.GetFileName(directoryPath);
                if (!preserved.Contains(name) && !name.StartsWith(InstallRootLayout.VersionDirectoryPrefix, StringComparison.OrdinalIgnoreCase))
                {
                    DeleteDirectoryIfExists(directoryPath);
                }
            }
        }

        private static void DeleteRegistryKeyTree(RegistryKey root, string path)
        {
            try { root.DeleteSubKeyTree(path, false); } catch (Exception exception) { InstallerLogger.Warn("Could not remove registry key " + path + ": " + exception.Message); }
        }

        private static void ScheduleDirectoryDeletion(string path)
        {
            Process.Start(new ProcessStartInfo
            {
                FileName = "cmd.exe",
                Arguments = "/c timeout /t 2 /nobreak >nul & if exist \"" + path + "\" rmdir /s /q \"" + path + "\"",
                CreateNoWindow = true,
                UseShellExecute = false
            });
        }

        private static void DeleteFileIfExists(string path)
        {
            try { if (File.Exists(path)) File.Delete(path); } catch (Exception exception) { InstallerLogger.Warn("Could not delete " + path + ": " + exception.Message); }
        }

        private static void DeleteDirectoryIfExists(string path)
        {
            try { if (Directory.Exists(path)) Directory.Delete(path, true); } catch (Exception exception) { InstallerLogger.Warn("Could not delete " + path + ": " + exception.Message); }
        }

        private static void LaunchApplication(string executablePath, string arguments, string workingDirectory)
        {
            Process.Start(new ProcessStartInfo
            {
                FileName = executablePath,
                Arguments = arguments,
                WorkingDirectory = workingDirectory,
                UseShellExecute = true
            });
        }

        private static string GetCurrentProcessPath()
        {
            return Process.GetCurrentProcess().MainModule?.FileName ?? Assembly.GetExecutingAssembly().Location;
        }

        private static string EnsureTrailingSeparator(string path)
        {
            return path.EndsWith(Path.DirectorySeparatorChar.ToString(), StringComparison.Ordinal) ? path : path + Path.DirectorySeparatorChar;
        }

        private static bool PathsEqual(string left, string right)
        {
            return string.Equals(
                Path.GetFullPath(left).TrimEnd(Path.DirectorySeparatorChar),
                Path.GetFullPath(right).TrimEnd(Path.DirectorySeparatorChar),
                StringComparison.OrdinalIgnoreCase);
        }
    }
}
