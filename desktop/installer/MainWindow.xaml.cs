using System;
using System.Threading.Tasks;
using System.Windows;
using System.Windows.Input;
using System.Windows.Media.Animation;

namespace Installer
{
    public partial class MainWindow : Window
    {
        public MainWindow()
        {
            InitializeComponent();
            Loaded += MainWindow_Loaded;
        }

        private async void MainWindow_Loaded(object sender, RoutedEventArgs e)
        {
            if (Resources["ProgressAnimation"] is Storyboard storyboard)
            {
                storyboard.Begin();
            }

            if (App.IsUninstallLaunch)
            {
                ConfigureForUninstall();
                return;
            }

            try
            {
                StatusText.Text = "Installing desktop app...";
                await InstallerLogic.RunInstallationAsync();
                StatusText.Text = "Launching Kino...";
                await Task.Delay(900);
                Application.Current.Shutdown();
            }
            catch (Exception exception)
            {
                InstallerLogger.Error("Interactive installation failed.", exception);
                StatusText.Text = "Install failed";
                DetailText.Text = InstallerLogger.AppendLogPath(exception.Message);
                DetailText.Visibility = Visibility.Visible;
                ProgressContainer.Visibility = Visibility.Collapsed;
            }
        }

        private void ConfigureForUninstall()
        {
            Title = "Kino Uninstall";
            StatusText.Text = "Uninstall Kino?";
            DetailText.Text = "This removes Kino, its shortcuts, and installer registration. Your app data is kept.";
            DetailText.Visibility = Visibility.Visible;
            ProgressContainer.Visibility = Visibility.Collapsed;
            ActionButtonsPanel.Visibility = Visibility.Visible;
        }

        private async void ConfirmButton_Click(object sender, RoutedEventArgs e)
        {
            ConfirmButton.IsEnabled = false;
            CancelButton.IsEnabled = false;
            ActionButtonsPanel.Visibility = Visibility.Collapsed;
            ProgressContainer.Visibility = Visibility.Visible;
            StatusText.Text = "Uninstalling...";
            DetailText.Text = "Removing Kino from this PC.";

            try
            {
                await InstallerLogic.RunUninstallationAsync();
                StatusText.Text = "Kino was removed.";
                await Task.Delay(900);
                Application.Current.Shutdown();
            }
            catch (Exception exception)
            {
                InstallerLogger.Error("Interactive uninstall failed.", exception);
                StatusText.Text = "Uninstall failed";
                DetailText.Text = InstallerLogger.AppendLogPath(exception.Message);
                ProgressContainer.Visibility = Visibility.Collapsed;
                ActionButtonsPanel.Visibility = Visibility.Visible;
                ConfirmButton.IsEnabled = true;
                CancelButton.IsEnabled = true;
            }
        }

        private void CancelButton_Click(object sender, RoutedEventArgs e)
        {
            Application.Current.Shutdown();
        }

        private void Window_MouseLeftButtonDown(object sender, MouseButtonEventArgs e)
        {
            try { DragMove(); } catch { }
        }
    }
}
