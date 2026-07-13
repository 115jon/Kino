using System;
using System.Collections.Generic;
using System.IO;
using System.Linq;

namespace Installer
{
    public sealed class InstallRootLayout
    {
        public const string DefaultInstallDirectoryName = "Kino";
        public const string CurrentStateFileName = "current.json";
        public const string RootLauncherFileName = "Update.exe";
        public const string RootIconFileName = "app.ico";
        public const string StagingDirectoryName = "staging";
        public const string LogsDirectoryName = "logs";
        public const string VersionDirectoryPrefix = "app-";

        private static readonly string[] PreservedRootEntries =
        {
            CurrentStateFileName,
            RootLauncherFileName,
            RootIconFileName,
            StagingDirectoryName,
            LogsDirectoryName
        };

        public InstallRootLayout(string rootPath)
        {
            if (string.IsNullOrWhiteSpace(rootPath))
            {
                throw new ArgumentException("Install root path is required.", nameof(rootPath));
            }

            RootPath = Path.GetFullPath(rootPath);
        }

        public string RootPath { get; }
        public string UpdateExePath => Path.Combine(RootPath, RootLauncherFileName);
        public string CurrentStatePath => Path.Combine(RootPath, CurrentStateFileName);
        public string RootIconPath => Path.Combine(RootPath, RootIconFileName);
        public string StagingPath => Path.Combine(RootPath, StagingDirectoryName);
        public string LogsPath => Path.Combine(RootPath, LogsDirectoryName);

        public string GetVersionDirectoryPath(string version)
        {
            string normalized = NormalizeVersion(version);
            if (string.IsNullOrWhiteSpace(normalized))
            {
                throw new ArgumentException("Version is required.", nameof(version));
            }

            return Path.Combine(RootPath, VersionDirectoryPrefix + normalized);
        }

        public string CreateUniqueStagingDirectoryPath(string versionHint)
        {
            string normalized = NormalizeVersion(versionHint);
            string name = string.IsNullOrWhiteSpace(normalized) ? "app" : VersionDirectoryPrefix + normalized;
            return Path.Combine(StagingPath, name + "." + Guid.NewGuid().ToString("N") + ".tmp");
        }

        public IEnumerable<string> EnumerateVersionDirectoryPaths()
        {
            if (!Directory.Exists(RootPath))
            {
                return Enumerable.Empty<string>();
            }

            return Directory.GetDirectories(RootPath, VersionDirectoryPrefix + "*", SearchOption.TopDirectoryOnly);
        }

        public IEnumerable<string> GetCleanupCandidateDirectoryPaths(InstallState state)
        {
            HashSet<string> protectedPaths = new HashSet<string>(
                (state ?? new InstallState()).GetProtectedVersions()
                    .Select(GetVersionDirectoryPath),
                StringComparer.OrdinalIgnoreCase);

            return EnumerateVersionDirectoryPaths()
                .Where(path => !protectedPaths.Contains(path))
                .ToArray();
        }

        public IEnumerable<string> GetInstalledExecutablePaths(string executableName)
        {
            List<string> paths = new List<string> { Path.Combine(RootPath, executableName), UpdateExePath };
            paths.AddRange(EnumerateVersionDirectoryPaths().Select(path => Path.Combine(path, executableName)));
            return paths.Distinct(StringComparer.OrdinalIgnoreCase).ToArray();
        }

        public IEnumerable<string> GetPreservedRootEntryNames()
        {
            return PreservedRootEntries;
        }

        public static InstallRootLayout FromLocalAppData(string localAppData)
        {
            return new InstallRootLayout(Path.Combine(localAppData, DefaultInstallDirectoryName));
        }

        public static InstallRootLayout FromExecutablePath(string executablePath)
        {
            string directory = Path.GetDirectoryName(Path.GetFullPath(executablePath));
            if (string.IsNullOrWhiteSpace(directory))
            {
                throw new InvalidOperationException("Executable path has no parent directory.");
            }

            return new InstallRootLayout(directory);
        }

        public static string NormalizeVersion(string version)
        {
            if (string.IsNullOrWhiteSpace(version))
            {
                return string.Empty;
            }

            string normalized = version.Trim();
            if (normalized.StartsWith(VersionDirectoryPrefix, StringComparison.OrdinalIgnoreCase))
            {
                normalized = normalized.Substring(VersionDirectoryPrefix.Length);
            }

            foreach (char invalidCharacter in Path.GetInvalidFileNameChars())
            {
                normalized = normalized.Replace(invalidCharacter, '-');
            }

            return normalized.Trim();
        }
    }
}
