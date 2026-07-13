using System;
using System.Diagnostics;
using System.IO;
using System.Linq;
using System.Text;

namespace Installer
{
    internal static class InstallerLogger
    {
        private static readonly object Sync = new object();
        private static string logFilePath;

        public static void Initialize(string[] args)
        {
            EnsureInitialized();
            Info("Kino installer started with args: " + FormatArgs(args));
        }

        public static void Info(string message) => Write("INFO", message, null);
        public static void Warn(string message) => Write("WARN", message, null);
        public static void Error(string message, Exception exception = null) => Write("ERROR", message, exception);

        public static string AppendLogPath(string message)
        {
            EnsureInitialized();
            return message + Environment.NewLine + "Installer log: " + logFilePath;
        }

        private static void EnsureInitialized()
        {
            if (logFilePath != null) return;
            lock (Sync)
            {
                if (logFilePath != null) return;
                string roaming = Environment.GetFolderPath(Environment.SpecialFolder.ApplicationData);
                string directory = Path.Combine(roaming, "Kino", "logs", "installer");
                Directory.CreateDirectory(directory);
                logFilePath = Path.Combine(directory, "installer-" + DateTime.Now.ToString("yyyyMMdd-HHmmss") + "-pid" + Process.GetCurrentProcess().Id + ".log");
            }
        }

        private static void Write(string level, string message, Exception exception)
        {
            EnsureInitialized();
            StringBuilder line = new StringBuilder()
                .Append('[').Append(DateTime.Now.ToString("yyyy-MM-dd HH:mm:ss.fff"))
                .Append("] [").Append(level).Append("] ").Append(message ?? string.Empty);
            if (exception != null) line.AppendLine().Append(exception);

            try
            {
                lock (Sync) File.AppendAllText(logFilePath, line + Environment.NewLine, Encoding.UTF8);
            }
            catch
            {
            }
        }

        private static string FormatArgs(string[] args)
        {
            return args == null || args.Length == 0
                ? "(none)"
                : string.Join(" ", args.Select(value => value.Contains(" ") ? "\"" + value + "\"" : value));
        }
    }
}
