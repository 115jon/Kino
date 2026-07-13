using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.IO;
using System.Linq;

namespace Installer
{
    public sealed class LaunchRequest
    {
        public LaunchRequest(string executablePath, string workingDirectory, string arguments, string activeVersion)
        {
            ExecutablePath = executablePath;
            WorkingDirectory = workingDirectory;
            Arguments = arguments ?? string.Empty;
            ActiveVersion = activeVersion;
        }

        public string ExecutablePath { get; }
        public string WorkingDirectory { get; }
        public string Arguments { get; }
        public string ActiveVersion { get; }
    }

    public static class LaunchCurrentVersion
    {
        public static bool IsLaunchRequest(string[] args)
        {
            return TryParse(args, out _);
        }

        public static LaunchRequest CreateLaunchRequest(string launcherPath, string[] args)
        {
            if (!TryParse(args, out LaunchCommand command))
            {
                throw new InvalidOperationException("No process-start command was provided.");
            }

            InstallRootLayout layout = InstallRootLayout.FromExecutablePath(launcherPath);
            InstallState state = InstallState.LoadIfExists(layout.CurrentStatePath);
            if (state == null || string.IsNullOrWhiteSpace(state.CurrentVersion))
            {
                throw new InvalidOperationException("Installed app state was not found.");
            }

            string executablePath = Path.Combine(
                layout.GetVersionDirectoryPath(state.CurrentVersion),
                Path.GetFileName(command.ProcessName));
            if (!File.Exists(executablePath))
            {
                throw new FileNotFoundException("The active Nuvio executable was not found.", executablePath);
            }

            return new LaunchRequest(
                executablePath,
                Path.GetDirectoryName(executablePath) ?? layout.RootPath,
                BuildArguments(command.PassthroughArguments, command.RawArguments),
                state.CurrentVersion);
        }

        public static void Execute(LaunchRequest request)
        {
            Process.Start(new ProcessStartInfo
            {
                FileName = request.ExecutablePath,
                Arguments = request.Arguments,
                WorkingDirectory = request.WorkingDirectory,
                UseShellExecute = true
            });
        }

        private static bool TryParse(string[] args, out LaunchCommand command)
        {
            command = null;
            if (args == null)
            {
                return false;
            }

            List<string> passthrough = new List<string>();
            string processName = null;
            string rawArguments = null;
            for (int index = 0; index < args.Length; index++)
            {
                if (string.Equals(args[index], "--processStart", StringComparison.OrdinalIgnoreCase))
                {
                    if (index + 1 >= args.Length) throw new InvalidOperationException("--processStart requires an executable name.");
                    processName = args[++index];
                }
                else if (string.Equals(args[index], "--process-start-args", StringComparison.OrdinalIgnoreCase))
                {
                    if (index + 1 >= args.Length) throw new InvalidOperationException("--process-start-args requires a value.");
                    rawArguments = args[++index];
                }
                else
                {
                    passthrough.Add(args[index]);
                }
            }

            if (string.IsNullOrWhiteSpace(processName))
            {
                return false;
            }

            command = new LaunchCommand(processName, passthrough, rawArguments);
            return true;
        }

        private static string BuildArguments(IEnumerable<string> passthrough, string rawArguments)
        {
            List<string> parts = passthrough.Select(QuoteIfNeeded).ToList();
            if (!string.IsNullOrWhiteSpace(rawArguments)) parts.Add(rawArguments);
            return string.Join(" ", parts);
        }

        private static string QuoteIfNeeded(string value)
        {
            return value.IndexOfAny(new[] { ' ', '\t', '"' }) < 0
                ? value
                : "\"" + value.Replace("\"", "\\\"") + "\"";
        }

        private sealed class LaunchCommand
        {
            public LaunchCommand(string processName, IEnumerable<string> passthroughArguments, string rawArguments)
            {
                ProcessName = processName;
                PassthroughArguments = passthroughArguments.ToArray();
                RawArguments = rawArguments;
            }

            public string ProcessName { get; }
            public IReadOnlyCollection<string> PassthroughArguments { get; }
            public string RawArguments { get; }
        }
    }
}
