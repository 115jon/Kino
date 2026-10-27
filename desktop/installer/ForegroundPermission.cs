using System.Runtime.InteropServices;

namespace Installer
{
    internal static class ForegroundPermission
    {
        private const int AnyProcess = -1;

        public static void AllowAny()
        {
            AllowSetForegroundWindow(AnyProcess);
        }

        [DllImport("user32.dll")]
        private static extern bool AllowSetForegroundWindow(int processId);
    }
}
