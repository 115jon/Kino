using System;
using System.Collections.Generic;
using System.IO;
using System.Linq;
using System.Runtime.Serialization;
using System.Runtime.Serialization.Json;
using System.Text;

namespace Installer
{
    [DataContract]
    public sealed class InstallState
    {
        private static readonly DataContractJsonSerializer Serializer =
            new DataContractJsonSerializer(typeof(InstallState));

        [DataMember(Name = "currentVersion", EmitDefaultValue = false)]
        public string CurrentVersion { get; set; }

        [DataMember(Name = "previousVersion", EmitDefaultValue = false)]
        public string PreviousVersion { get; set; }

        [DataMember(Name = "activatedAtUtc", EmitDefaultValue = false)]
        public string ActivatedAtUtc { get; set; }

        [DataMember(Name = "pendingCleanup", EmitDefaultValue = false)]
        public List<string> PendingCleanup { get; set; }

        public IEnumerable<string> GetProtectedVersions()
        {
            return new[] { CurrentVersion, PreviousVersion }
                .Where(value => !string.IsNullOrWhiteSpace(value))
                .Select(value => value.Trim())
                .Distinct(StringComparer.OrdinalIgnoreCase);
        }

        public void MarkActivatedNowUtc()
        {
            ActivatedAtUtc = DateTime.UtcNow.ToString("o");
        }

        public void Save(string path)
        {
            string fullPath = Path.GetFullPath(path);
            Directory.CreateDirectory(Path.GetDirectoryName(fullPath));
            string temporaryPath = fullPath + ".tmp";

            using (FileStream stream = File.Create(temporaryPath))
            {
                Serializer.WriteObject(stream, this);
            }

            if (File.Exists(fullPath))
            {
                File.Replace(temporaryPath, fullPath, null);
            }
            else
            {
                File.Move(temporaryPath, fullPath);
            }
        }

        public static InstallState LoadIfExists(string path)
        {
            if (!File.Exists(path))
            {
                return null;
            }

            using (FileStream stream = File.OpenRead(path))
            {
                return Serializer.ReadObject(stream) as InstallState;
            }
        }
    }
}
