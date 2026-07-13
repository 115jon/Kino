using System;
using System.Diagnostics;
using System.Linq;
using System.Windows;
using System.Windows.Threading;

namespace Installer
{
    public partial class App : Application
    {
        public static bool IsUninstallLaunch { get; private set; }

        protected override void OnStartup(StartupEventArgs e)
        {
            InstallerLogger.Initialize(e.Args);

            if (LaunchCurrentVersion.IsLaunchRequest(e.Args))
            {
                try
                {
                    LaunchCurrentVersion.Execute(LaunchCurrentVersion.CreateLaunchRequest(GetCurrentProcessPath(), e.Args));
                    Environment.Exit(0);
                }
                catch (Exception exception)
                {
                    InstallerLogger.Error("Could not launch the active Kino version.", exception);
                    MessageBox.Show(InstallerLogger.AppendLogPath(exception.Message), "Kino Launch Failed", MessageBoxButton.OK, MessageBoxImage.Error);
                    Environment.Exit(1);
                }
            }

            bool silent = e.Args.Any(IsSilentArgument);
            IsUninstallLaunch = e.Args.Any(IsUninstallArgument);
            DispatcherUnhandledException += (_, args) => InstallerLogger.Error("Unhandled installer exception.", args.Exception);

            if (IsUninstallLaunch && silent)
            {
                RunSilent(InstallerLogic.RunUninstallationAsync, "Silent uninstall failed.");
            }

            if (silent)
            {
                RunSilent(InstallerLogic.RunInstallationAsync, "Silent installation failed.");
            }

            base.OnStartup(e);
        }

        private static void RunSilent(Func<System.Threading.Tasks.Task> operation, string failureMessage)
        {
            try
            {
                operation().GetAwaiter().GetResult();
                Environment.Exit(0);
            }
            catch (Exception exception)
            {
                InstallerLogger.Error(failureMessage, exception);
                Environment.Exit(1);
            }
        }

        private static bool IsSilentArgument(string value)
        {
            return string.Equals(value, "/S", StringComparison.OrdinalIgnoreCase)
                || string.Equals(value, "--silent", StringComparison.OrdinalIgnoreCase)
                || string.Equals(value, "--passive", StringComparison.OrdinalIgnoreCase);
        }

        private static bool IsUninstallArgument(string value)
        {
            return string.Equals(value, "--uninstall", StringComparison.OrdinalIgnoreCase)
                || string.Equals(value, "/uninstall", StringComparison.OrdinalIgnoreCase);
        }

        private static string GetCurrentProcessPath()
        {
            return Process.GetCurrentProcess().MainModule?.FileName ?? AppDomain.CurrentDomain.BaseDirectory;
        }
    }
}
