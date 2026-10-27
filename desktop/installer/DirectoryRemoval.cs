using System.IO;

namespace Installer
{
    public static class DirectoryRemoval
    {
        public static void DeleteRecursive(string path)
        {
            if (!Directory.Exists(path)) return;
            foreach (string filePath in Directory.EnumerateFiles(path, "*", SearchOption.AllDirectories))
            {
                FileAttributes attributes = File.GetAttributes(filePath);
                if ((attributes & FileAttributes.ReadOnly) != 0)
                {
                    File.SetAttributes(filePath, attributes & ~FileAttributes.ReadOnly);
                }
            }
            Directory.Delete(path, true);
        }
    }
}
