package me.eigenraven.lwjgl3ify.relauncher;

import java.awt.Dialog;
import java.awt.Dimension;
import java.awt.GraphicsEnvironment;
import java.awt.event.WindowAdapter;
import java.awt.event.WindowEvent;
import java.io.File;
import java.lang.management.ManagementFactory;
import java.lang.reflect.InvocationTargetException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.concurrent.Callable;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;

import javax.management.MBeanServer;
import javax.management.ObjectName;
import javax.swing.ComboBoxModel;
import javax.swing.DefaultComboBoxModel;
import javax.swing.JDialog;
import javax.swing.JFileChooser;
import javax.swing.JFrame;
import javax.swing.JOptionPane;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.UIManager;
import javax.swing.filechooser.FileFilter;
import javax.swing.text.DefaultEditorKit;

import org.apache.commons.lang3.StringUtils;

import com.google.common.base.Throwables;

import me.eigenraven.lwjgl3ify.relauncher.runtime.AutomaticRuntimeResult;

/** Implements the UI components of the relauncher */
/*
 * Dev note on recompiling forms:
 * 1. Import into IntelliJ as a gradle project
 * 2. In gradle settings, change Build with: from Gradle to IntelliJ
 * 3. Open the .form file in the form editor and use the main menu Build->Recompile 'currentfile.form' function
 * 4. You might have to remove the build/libs and build/distributions files afterwards, IntelliJ messes with Gradle's
 * caching
 */
public class RelauncherUserInterface {

    private final Relauncher relauncher;
    private boolean swingInitialized = false;

    public RelauncherUserInterface(Relauncher relauncher) {
        this.relauncher = relauncher;
    }

    // Only call from the Swing thread
    private void initSwingIfNeeded() {
        if (swingInitialized) {
            return;
        }
        swingInitialized = true;
        final ClassLoader original = Thread.currentThread()
            .getContextClassLoader();
        final ClassLoader mcLoader = RelauncherUserInterface.class.getClassLoader();
        Thread.currentThread()
            .setContextClassLoader(mcLoader);
        try {
            System.setProperty("awt.useSystemAAFontSettings", "on");
            if (System.getProperty("os.name")
                .toLowerCase(Locale.ROOT)
                .contains("linux")) {
                UIManager.setLookAndFeel("javax.swing.plaf.nimbus.NimbusLookAndFeel");
            } else {
                UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
            }
        } catch (Exception e) {
            Relauncher.logger.warn("Could not initialize GUI theme", e);
        }
        Thread.currentThread()
            .setContextClassLoader(original);
    }

    private void invokeOnSwingThread(boolean wait, Runnable runnable) {
        try {
            if (wait) {
                SwingUtilities.invokeAndWait(runnable);
            } else {
                SwingUtilities.invokeLater(runnable);
            }
        } catch (InterruptedException e) {
            Thread.currentThread()
                .interrupt();
            throw new RuntimeException("Interrupted while waiting for the Swing event thread", e);
        } catch (InvocationTargetException e) {
            throw Throwables.propagate(e.getCause());
        }
    }

    public void downloadWithGui(Downloader dler) {
        invokeOnSwingThread(true, () -> {
            initSwingIfNeeded();

            final int totalTasks = dler.remainingTasks();
            final JDialog progressDialog = new JDialog(
                null,
                "Lwjgl3ify Library Downloader",
                Dialog.ModalityType.APPLICATION_MODAL);
            progressDialog.setMinimumSize(new Dimension(400, 128));
            progressDialog.setSize(new Dimension(400, 128));
            progressDialog.setLocationRelativeTo(null);
            progressDialog.setResizable(false);
            progressDialog.setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);

            final ProgressDialog dialogContent = new ProgressDialog();
            dialogContent.loadTranslations();
            progressDialog.add(dialogContent.panel);

            dialogContent.progressBar.setMinimum(0);
            dialogContent.progressBar.setMaximum(totalTasks);
            dialogContent.progressBar.setValue(0);
            dialogContent.cancelButton.addActionListener(al -> { relauncher.runtimeExit(1); });

            final Thread dlThread = new Thread(dler::runDownloads, "Download thread");
            dlThread.setContextClassLoader(RelauncherUserInterface.class.getClassLoader());
            dlThread.setDaemon(true);

            final AtomicBoolean normallyTerminated = new AtomicBoolean(false);
            final AtomicReference<Throwable> dlException = new AtomicReference<>(null);
            dlThread.setUncaughtExceptionHandler((_thread, exception) -> dlException.set(exception));

            final Timer updater = new Timer(1000 / 120, al -> {
                final int remaining = dler.remainingTasks();
                dialogContent.progressBar.setValue(totalTasks - remaining);
                final String files = StringUtils.join(dler.busyDownloads, ", ");
                dialogContent.filesLabel.setText(files);
                if (remaining <= 0) {
                    try {
                        dlThread.join();
                    } catch (InterruptedException e) {
                        throw new RuntimeException(e);
                    }
                    final Throwable exception = dlException.get();
                    if (exception != null) {
                        dialogContent.filesLabel.setText(
                            "Exception happened when downloading required files, check the log for details.\n"
                                + exception);
                        throw Throwables.propagate(exception);
                    }
                    normallyTerminated.set(true);
                    progressDialog.dispose();
                }
            });
            updater.start();

            progressDialog.addWindowListener(new WindowAdapter() {

                @Override
                public void windowClosed(WindowEvent e) {
                    if (!normallyTerminated.get()) {
                        relauncher.runtimeExit(1);
                    }
                }

                @Override
                public void windowOpened(WindowEvent e) {
                    dlThread.start();
                }
            });

            progressDialog.setVisible(true);
        });
    }

    private static long getTotalMemoryBytes() {
        try {
            MBeanServer mBeanServer = ManagementFactory.getPlatformMBeanServer();
            Object attribute = mBeanServer
                .getAttribute(new ObjectName("java.lang", "type", "OperatingSystem"), "TotalPhysicalMemorySize");
            return Long.parseLong(attribute.toString());
        } catch (Exception e) {
            return 8192;
        }
    }

    public AutomaticRuntimeResult prepareAutomaticRuntime(Callable<AutomaticRuntimeResult> preparation) {
        if (preparation == null) throw new NullPointerException("preparation");
        if (GraphicsEnvironment.isHeadless()) {
            try {
                return preparation.call();
            } catch (Exception exception) {
                throw Throwables.propagate(exception);
            }
        }

        final AtomicReference<AutomaticRuntimeResult> result = new AtomicReference<>();
        final AtomicReference<Throwable> failure = new AtomicReference<>();
        final Thread worker = new Thread(() -> {
            try {
                result.set(preparation.call());
            } catch (Throwable throwable) {
                failure.set(throwable);
            }
        }, "Packaged Java preparation");
        worker.setContextClassLoader(RelauncherUserInterface.class.getClassLoader());
        worker.setDaemon(true);
        worker.start();

        final AtomicReference<JDialog> progress = new AtomicReference<>();
        try {
            worker.join(300L);
            if (worker.isAlive()) {
                invokeOnSwingThread(true, () -> {
                    initSwingIfNeeded();
                    final JDialog progressDialog = new JDialog(
                        null,
                        "Preparing packaged Java",
                        Dialog.ModalityType.MODELESS);
                    progressDialog.setMinimumSize(new Dimension(480, 128));
                    progressDialog.setSize(new Dimension(480, 128));
                    progressDialog.setLocationRelativeTo(null);
                    progressDialog.setResizable(false);
                    progressDialog.setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);
                    final ProgressDialog contents = new ProgressDialog();
                    contents.statusLabel.setText("Preparing packaged Java 21 for the first launch...");
                    contents.filesLabel.setText("The validated runtime will be reused on later launches.");
                    contents.progressBar.setIndeterminate(true);
                    contents.cancelButton.setVisible(false);
                    progressDialog.add(contents.panel);
                    progress.set(progressDialog);
                    progressDialog.setVisible(true);
                });
            }
            while (worker.isAlive()) {
                worker.join(100L);
            }
        } catch (InterruptedException exception) {
            worker.interrupt();
            Thread.currentThread()
                .interrupt();
            throw new RuntimeException("Interrupted while preparing packaged Java", exception);
        } finally {
            final JDialog progressDialog = progress.get();
            if (progressDialog != null) {
                invokeOnSwingThread(true, progressDialog::dispose);
            }
        }
        if (failure.get() != null) throw Throwables.propagate(failure.get());
        return result.get();
    }

    public void showAutomaticRuntimeFailure(AutomaticRuntimeResult automatic) {
        if (GraphicsEnvironment.isHeadless()) return;
        invokeOnSwingThread(true, () -> {
            initSwingIfNeeded();
            JOptionPane.showMessageDialog(
                null,
                automatic.getMessage() + "\n\nRecovery: launch once with -D"
                    + me.eigenraven.lwjgl3ify.relauncher.runtime.AutomaticRuntimeCoordinator.DISABLE_PROPERTY
                    + "=true to use manual Java selection.",
                "Packaged Java preparation failed",
                JOptionPane.ERROR_MESSAGE);
        });
    }

    public LaunchDecision showSettings(AutomaticRuntimeResult automatic) {
        final SettingsLaunchController launchController = new SettingsLaunchController();
        invokeOnSwingThread(true, () -> {
            initSwingIfNeeded();
            final JDialog settingsDialog = new JDialog(
                null,
                "Lwjgl3ify settings",
                Dialog.ModalityType.APPLICATION_MODAL);
            settingsDialog.setMinimumSize(new Dimension(800, 650));
            settingsDialog.setSize(new Dimension(800, 650));
            settingsDialog.setLocationRelativeTo(null);
            settingsDialog.setResizable(true);
            settingsDialog.setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);

            final SettingsDialog contents = new SettingsDialog();
            contents.loadTranslations();
            settingsDialog.add(contents.rootPanel);

            final long totalSystemMemory = getTotalMemoryBytes();
            final int totalSystemMemoryMB = (int) ((totalSystemMemory + (1024 * 1024) - 1) / (1024 * 1024));
            contents.optMinMemory.setMaximum(totalSystemMemoryMB);
            contents.optMaxMemory.setMaximum(totalSystemMemoryMB);
            int memTickSpacing = 1024;
            while ((totalSystemMemoryMB / memTickSpacing) > 9) {
                memTickSpacing *= 2;
            }
            contents.optMinMemory.setLabelTable(null);
            contents.optMaxMemory.setLabelTable(null);
            contents.optMinMemory.setMajorTickSpacing(memTickSpacing);
            contents.optMaxMemory.setMajorTickSpacing(memTickSpacing);
            contents.labelMinJavaVer
                .setText(String.format(contents.translations.getString(TranslationsBundle.KEY_MIN_MOD_JAVA), 17));

            final RelauncherConfig.ConfigObject initCfg = RelauncherConfig.config;
            final DefaultComboBoxModel<String> modelJavaPaths = new DefaultComboBoxModel<>(
                initCfg.javaInstallationsCache.clone());
            contents.comboJavaExecutable.setModel(modelJavaPaths);
            if (modelJavaPaths.getSize() > 0 && initCfg.javaInstallation >= 0
                && initCfg.javaInstallation < modelJavaPaths.getSize()) {
                contents.comboJavaExecutable.setSelectedIndex(initCfg.javaInstallation);
            }
            contents.optUseBundledJava.setSelected(initCfg.useBundledJava);
            contents.lblBundledStatus.setText(automatic.getMessage());
            contents.optMinMemory.setValue(initCfg.minMemoryMB);
            contents.optMaxMemory.setValue(initCfg.maxMemoryMB);
            contents.optGC.setModel(new GCComboModel());
            contents.optGC.setSelectedItem(initCfg.garbageCollector);
            contents.optCustom.getDocument()
                .putProperty(DefaultEditorKit.EndOfLineStringProperty, "\n");
            contents.optCustom.setText(initCfg.customOptionsToQuotedString());
            contents.optForwardLogs.setSelected(initCfg.forwardLogs);
            contents.optDebugAgent.setSelected(initCfg.allowDebugger);
            contents.optDebugSuspend.setSelected(initCfg.waitForDebugger);
            contents.optMixinDebug.setSelected(initCfg.mixinDebug);
            contents.optMixinExport.setSelected(initCfg.mixinDebugExport);
            contents.optMixinCount.setSelected(initCfg.mixinDebugCount);
            contents.optFmlDebugAts.setSelected(initCfg.fmlDebugAts);
            contents.optRfbDumpClasses.setSelected(initCfg.rfbDumpClasses);
            contents.optRfbDumpTransformers.setSelected(initCfg.rfbDumpPerTransformer);
            contents.optHideOnFutureLaunches.setSelected(initCfg.hideSettingsOnLaunch);

            refreshJavaInstalls(contents);

            settingsDialog.addWindowListener(new WindowAdapter() {

                @Override
                public void windowClosing(WindowEvent e) {
                    saveConfig(contents);
                }
            });

            contents.buttonRefreshJavas.addActionListener(al -> refreshJavaInstalls(contents));
            contents.buttonAddJava.addActionListener(al -> {
                final JFileChooser jfc = new JFileChooser();
                jfc.setFileSelectionMode(JFileChooser.FILES_ONLY);
                jfc.setDialogType(JFileChooser.OPEN_DIALOG);
                jfc.setDialogTitle("Pick java executable");
                final FileFilter fileFilter = new FileFilter() {

                    @Override
                    public boolean accept(File f) {
                        if (f == null) return false;
                        final String name = f.getName();
                        return !f.isFile() || name.equalsIgnoreCase("java")
                            || name.equalsIgnoreCase("javaw")
                            || name.equalsIgnoreCase("java.exe")
                            || name.equalsIgnoreCase("javaw.exe");
                    }

                    @Override
                    public String getDescription() {
                        return "Java binaries";
                    }
                };
                jfc.addChoosableFileFilter(fileFilter);
                jfc.setFileFilter(fileFilter);
                final int resultValue = jfc.showOpenDialog(settingsDialog);
                if (resultValue == JFileChooser.APPROVE_OPTION) {
                    final String path = jfc.getSelectedFile()
                        .getAbsolutePath();
                    final DefaultComboBoxModel<String> model = (DefaultComboBoxModel<String>) contents.comboJavaExecutable
                        .getModel();
                    model.addElement(path);
                    model.setSelectedItem(path);
                    refreshJavaInstalls(contents);
                }
            });

            contents.buttonPreviewJavaOpts.addActionListener(al -> {
                saveConfig(contents);
                JOptionPane.showMessageDialog(
                    settingsDialog,
                    String.join(System.lineSeparator(), RelauncherConfig.config.toJvmArgs()),
                    "Java",
                    JOptionPane.INFORMATION_MESSAGE);
            });

            contents.buttonRun.addActionListener(al -> {
                saveConfig(contents);
                launchController.proceed();
                settingsDialog.dispose();
            });

            settingsDialog.setVisible(true);
        });
        return launchController.getDecision();
    }

    private static void refreshJavaInstalls(SettingsDialog contents) {
        final DefaultComboBoxModel<String> model = (DefaultComboBoxModel<String>) contents.comboJavaExecutable
            .getModel();
        final String oldSelection = Objects.toString(model.getSelectedItem());
        final List<Path> validInstalls = JvmLocator.detectJavaInstalls(comboToList(model));
        model.removeAllElements();
        for (final Path p : validInstalls) {
            final String pString = p.toString();
            model.addElement(pString);
            if (pString.equals(oldSelection)) {
                model.setSelectedItem(pString);
            }
        }
    }

    private static <T> List<T> comboToList(ComboBoxModel<T> model) {
        final List<T> out = new ArrayList<>(model.getSize());
        for (int i = 0; i < model.getSize(); i++) {
            out.add(model.getElementAt(i));
        }
        return out;
    }

    private static void saveConfig(SettingsDialog contents) {
        final RelauncherConfig.ConfigObject initCfg = RelauncherConfig.config;
        initCfg.javaInstallationsCache = comboToList(contents.comboJavaExecutable.getModel()).toArray(new String[0]);
        initCfg.javaInstallation = contents.comboJavaExecutable.getSelectedIndex();
        initCfg.minMemoryMB = contents.optMinMemory.getValue();
        initCfg.maxMemoryMB = contents.optMaxMemory.getValue();
        initCfg.garbageCollector = (RelauncherConfig.GCOption) contents.optGC.getSelectedItem();
        initCfg.setCustomOptionsFromQuotedString(
            contents.optCustom.getText()
                .replace("\r\n", "\n"));
        initCfg.forwardLogs = contents.optForwardLogs.isSelected();
        initCfg.allowDebugger = contents.optDebugAgent.isSelected();
        initCfg.waitForDebugger = contents.optDebugSuspend.isSelected();
        initCfg.mixinDebug = contents.optMixinDebug.isSelected();
        initCfg.mixinDebugExport = contents.optMixinExport.isSelected();
        initCfg.mixinDebugCount = contents.optMixinCount.isSelected();
        initCfg.fmlDebugAts = contents.optFmlDebugAts.isSelected();
        initCfg.rfbDumpClasses = contents.optRfbDumpClasses.isSelected();
        initCfg.rfbDumpPerTransformer = contents.optRfbDumpTransformers.isSelected();
        initCfg.hideSettingsOnLaunch = contents.optHideOnFutureLaunches.isSelected();
        initCfg.useBundledJava = contents.optUseBundledJava.isSelected();
        RelauncherConfig.save();
    }

    public static class GCComboModel extends DefaultComboBoxModel<RelauncherConfig.GCOption> {

        public GCComboModel() {
            super(RelauncherConfig.GCOption.values());
        }
    }
}
