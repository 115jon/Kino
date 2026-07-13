using System;
using System.Collections.Generic;
using System.IO;
using System.Linq;

namespace Installer
{
    public sealed class ActivationResult
    {
        public ActivationResult(string activeVersion, string activeDirectoryPath, InstallState installState)
        {
            ActiveVersion = activeVersion;
            ActiveDirectoryPath = activeDirectoryPath;
            InstallState = installState;
        }

        public string ActiveVersion { get; }
        public string ActiveDirectoryPath { get; }
        public InstallState InstallState { get; }
        public string ActiveExecutablePath => Path.Combine(ActiveDirectoryPath, InstallerLogic.ExecutableFileName);
    }

    public static class ActivationManager
    {
        public static string PrepareStagingDirectory(InstallRootLayout layout, string versionHint)
        {
            Directory.CreateDirectory(layout.RootPath);
            Directory.CreateDirectory(layout.StagingPath);

            foreach (string stalePath in Directory.GetDirectories(layout.StagingPath))
            {
                TryDeleteDirectory(stalePath);
            }

            string stagingPath = layout.CreateUniqueStagingDirectoryPath(versionHint);
            Directory.CreateDirectory(stagingPath);
            return stagingPath;
        }

        public static ActivationResult Activate(InstallRootLayout layout, string stagingPath, string version)
        {
            string normalizedVersion = InstallRootLayout.NormalizeVersion(version);
            ValidatePayload(stagingPath);

            InstallState previousState = InstallState.LoadIfExists(layout.CurrentStatePath);
            string targetPath = layout.GetVersionDirectoryPath(normalizedVersion);
            if (Directory.Exists(targetPath))
            {
                Directory.Delete(targetPath, true);
            }

            Directory.Move(stagingPath, targetPath);

            InstallState state = new InstallState
            {
                CurrentVersion = normalizedVersion,
                PreviousVersion = previousState?.CurrentVersion,
                PendingCleanup = new List<string>()
            };
            state.MarkActivatedNowUtc();
            state.Save(layout.CurrentStatePath);

            List<string> pendingCleanup = new List<string>();
            foreach (string obsoletePath in layout.GetCleanupCandidateDirectoryPaths(state))
            {
                if (!TryDeleteDirectory(obsoletePath))
                {
                    pendingCleanup.Add(Path.GetFileName(obsoletePath));
                }
            }

            state.PendingCleanup = pendingCleanup.Distinct(StringComparer.OrdinalIgnoreCase).ToList();
            state.Save(layout.CurrentStatePath);
            return new ActivationResult(normalizedVersion, targetPath, state);
        }

        private static void ValidatePayload(string stagingPath)
        {
            if (!Directory.Exists(stagingPath))
            {
                throw new DirectoryNotFoundException("Staged payload directory was not found: " + stagingPath);
            }

            string executablePath = Path.Combine(stagingPath, InstallerLogic.ExecutableFileName);
            if (!File.Exists(executablePath))
            {
                throw new FileNotFoundException("The desktop executable was missing from the installer payload.", executablePath);
            }
        }

        private static bool TryDeleteDirectory(string path)
        {
            try
            {
                if (Directory.Exists(path))
                {
                    Directory.Delete(path, true);
                }

                return true;
            }
            catch (Exception exception)
            {
                InstallerLogger.Warn("Could not delete " + path + ": " + exception.Message);
                return false;
            }
        }
    }
}
