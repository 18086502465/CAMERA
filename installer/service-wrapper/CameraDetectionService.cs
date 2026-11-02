using System;
using System.Diagnostics;
using System.IO;
using System.Linq;
using System.ServiceProcess;
using System.Threading;

namespace CameraDetectionSystem.ServiceWrapper
{
    public class CameraDetectionService : ServiceBase
    {
        private const string ServiceDisplayName = "Camera Detection System";
        private Process _javaProcess;
        private string _installDir;
        private string _workDir;
        private string _logDir;

        public CameraDetectionService()
        {
            ServiceName = "CameraDetectionSystem";
            CanStop = true;
            CanShutdown = true;
            AutoLog = true;
        }

        public static void Main(string[] args)
        {
            var service = new CameraDetectionService();

            if (Environment.UserInteractive)
            {
                Console.WriteLine(ServiceDisplayName + " console mode starting...");
                service.OnStart(args);
                Console.WriteLine("Started. Press ENTER to stop.");
                Console.ReadLine();
                service.OnStop();
                return;
            }

            Run(service);
        }

        protected override void OnStart(string[] args)
        {
            _installDir = ResolveInstallDir();
            _workDir = ResolveWorkDir();
            _logDir = Path.Combine(_workDir, "logs");
            Directory.CreateDirectory(_logDir);
            Directory.CreateDirectory(Path.Combine(_workDir, "data", "uploads", "defect-images"));
            Directory.CreateDirectory(Path.Combine(_workDir, "data", "uploads", "images"));

            var javaExe = ResolveJavaExe();
            var jarPath = Path.Combine(_installDir, "app", "time-bucket-demo.jar");
            var configPath = Path.Combine(_installDir, "config", "application.yml");

            if (!File.Exists(jarPath))
            {
                throw new FileNotFoundException("Spring Boot jar not found: " + jarPath);
            }
            if (!File.Exists(configPath))
            {
                throw new FileNotFoundException("External application.yml not found: " + configPath);
            }

            var argsLine =
                "-Dfile.encoding=UTF-8 " +
                "-Dapp.home=\"" + _workDir + "\" " +
                "-jar \"" + jarPath + "\" " +
                "--spring.config.additional-location=\"file:" + configPath.Replace("\\", "/") + "\"";

            var startInfo = new ProcessStartInfo
            {
                FileName = javaExe,
                Arguments = argsLine,
                WorkingDirectory = _workDir,
                UseShellExecute = false,
                CreateNoWindow = true,
                RedirectStandardOutput = true,
                RedirectStandardError = true
            };

            _javaProcess = new Process { StartInfo = startInfo, EnableRaisingEvents = true };
            _javaProcess.OutputDataReceived += (sender, eventArgs) => AppendLine("service-out.log", eventArgs.Data);
            _javaProcess.ErrorDataReceived += (sender, eventArgs) => AppendLine("service-err.log", eventArgs.Data);
            _javaProcess.Exited += (sender, eventArgs) =>
            {
                AppendLine("service-wrapper.log", "Java process exited. ExitCode=" + SafeExitCode(_javaProcess));
            };

            _javaProcess.Start();
            _javaProcess.BeginOutputReadLine();
            _javaProcess.BeginErrorReadLine();

            AppendLine("service-wrapper.log", "Started java process. PID=" + _javaProcess.Id + ", Java=" + javaExe + ", InstallDir=" + _installDir + ", WorkDir=" + _workDir);
        }

        protected override void OnStop()
        {
            StopJavaProcess();
        }

        protected override void OnShutdown()
        {
            StopJavaProcess();
        }

        private void StopJavaProcess()
        {
            try
            {
                if (_javaProcess == null || _javaProcess.HasExited)
                {
                    return;
                }

                AppendLine("service-wrapper.log", "Stopping java process. PID=" + _javaProcess.Id);
                _javaProcess.Kill();
                _javaProcess.WaitForExit(15000);
                AppendLine("service-wrapper.log", "Stopped java process.");
            }
            catch (Exception ex)
            {
                AppendLine("service-wrapper.log", "Stop failed: " + ex);
            }
        }

        private string ResolveInstallDir()
        {
            return Path.GetFullPath(Path.Combine(AppDomain.CurrentDomain.BaseDirectory, ".."));
        }

        private string ResolveWorkDir()
        {
            var workDirFile = Path.Combine(_installDir, "config", "workdir.txt");
            if (File.Exists(workDirFile))
            {
                var configured = File.ReadAllText(workDirFile).Trim();
                if (!string.IsNullOrWhiteSpace(configured))
                {
                    return Path.GetFullPath(configured);
                }
            }
            return _installDir;
        }

        private string ResolveJavaExe()
        {
            var bundled = Path.Combine(_installDir, "jre", "bin", "java.exe");
            if (File.Exists(bundled))
            {
                return bundled;
            }

            var javaHome = Environment.GetEnvironmentVariable("JAVA_HOME");
            if (!string.IsNullOrWhiteSpace(javaHome))
            {
                var javaFromHome = Path.Combine(javaHome, "bin", "java.exe");
                if (File.Exists(javaFromHome))
                {
                    return javaFromHome;
                }
                var javaFromJre = Path.Combine(javaHome, "jre", "bin", "java.exe");
                if (File.Exists(javaFromJre))
                {
                    return javaFromJre;
                }
            }

            var path = Environment.GetEnvironmentVariable("PATH") ?? string.Empty;
            foreach (var dir in path.Split(';').Where(x => !string.IsNullOrWhiteSpace(x)))
            {
                try
                {
                    var candidate = Path.Combine(dir.Trim(), "java.exe");
                    if (File.Exists(candidate))
                    {
                        return candidate;
                    }
                }
                catch
                {
                    // ignore malformed PATH segment
                }
            }

            throw new FileNotFoundException("java.exe not found. Please install JDK/JRE 8+ or package jre directory.");
        }

        private void AppendLine(string fileName, string line)
        {
            if (string.IsNullOrEmpty(line))
            {
                return;
            }
            try
            {
                Directory.CreateDirectory(_logDir);
                File.AppendAllText(
                    Path.Combine(_logDir, fileName),
                    DateTime.Now.ToString("yyyy-MM-dd HH:mm:ss.fff") + " " + line + Environment.NewLine);
            }
            catch
            {
                // Windows Service logging must not break service lifecycle.
            }
        }

        private int SafeExitCode(Process process)
        {
            try
            {
                return process == null ? -1 : process.ExitCode;
            }
            catch
            {
                return -1;
            }
        }
    }
}
