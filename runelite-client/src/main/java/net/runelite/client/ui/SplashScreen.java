/*
 * Copyright (c) 2019 Abex
 * All rights reserved.
 *
 * Redistribution and use in source and binary forms, with or without
 * modification, are permitted provided that the following conditions are met:
 *
 * 1. Redistributions of source code must retain the above copyright notice, this
 *    list of conditions and the following disclaimer.
 * 2. Redistributions in binary form must reproduce the above copyright notice,
 *    this list of conditions and the following disclaimer in the documentation
 *    and/or other materials provided with the distribution.
 *
 * THIS SOFTWARE IS PROVIDED BY THE COPYRIGHT HOLDERS AND CONTRIBUTORS "AS IS" AND
 * ANY EXPRESS OR IMPLIED WARRANTIES, INCLUDING, BUT NOT LIMITED TO, THE IMPLIED
 * WARRANTIES OF MERCHANTABILITY AND FITNESS FOR A PARTICULAR PURPOSE ARE
 * DISCLAIMED. IN NO EVENT SHALL THE COPYRIGHT OWNER OR CONTRIBUTORS BE LIABLE FOR
 * ANY DIRECT, INDIRECT, INCIDENTAL, SPECIAL, EXEMPLARY, OR CONSEQUENTIAL DAMAGES
 * (INCLUDING, BUT NOT LIMITED TO, PROCUREMENT OF SUBSTITUTE GOODS OR SERVICES;
 * LOSS OF USE, DATA, OR PROFITS; OR BUSINESS INTERRUPTION) HOWEVER CAUSED AND
 * ON ANY THEORY OF LIABILITY, WHETHER IN CONTRACT, STRICT LIABILITY, OR TORT
 * (INCLUDING NEGLIGENCE OR OTHERWISE) ARISING IN ANY WAY OUT OF THE USE OF THIS
 * SOFTWARE, EVEN IF ADVISED OF THE POSSIBILITY OF SUCH DAMAGE.
 */

package net.runelite.client.ui;

import lombok.extern.slf4j.Slf4j;
import net.runelite.client.plugins.microbot.RandomFactClient;
import net.runelite.client.ui.laf.RuneLiteLAF;
import net.runelite.client.util.ImageUtil;

import javax.annotation.Nullable;
import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.plaf.basic.BasicProgressBarUI;
import java.awt.*;
import java.awt.event.ActionEvent;
import java.awt.event.ActionListener;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.beans.PropertyChangeSupport;
import java.lang.reflect.InvocationTargetException;
import java.util.Arrays;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

@Slf4j
public class SplashScreen extends JFrame implements ActionListener {
    private static final int WIDTH = 360;
    private static final int PAD = 14;
    private static final int ARC = 18;
    private static final int CLOSE_BUTTON_SIZE = 20;

    private static SplashScreen INSTANCE;

    private final JLabel action = new JLabel("Loading");
    private final JProgressBar progress = new JProgressBar();
    private final JLabel subAction = new JLabel();
    private final Timer timer;

    private volatile double overallProgress = 0;
    private volatile String actionText = "Loading";
    private volatile String subActionText = "";
    private volatile String progressText = null;

    private final PropertyChangeSupport pcs = new PropertyChangeSupport(this);
    private static String factValue = "Fetching a tip...";

    public static String getFact() {
        return factValue;
    }

    public static void setFact(String newFact) {
        if (INSTANCE != null && newFact != null && !String.valueOf(factValue).equals(newFact)) {
            String oldValue = factValue;
            factValue = newFact;
            INSTANCE.pcs.firePropertyChange("fact", oldValue, newFact);
        }
    }

    private static ScheduledExecutorService scheduledRandomFactExecutorService;
    private static ScheduledFuture<?> scheduledRandomFactFuture;

    static String escape(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
    }

    private JPanel createCloseButton() {
        JPanel closePanel = new JPanel();
        closePanel.setOpaque(false);
        closePanel.setLayout(new FlowLayout(FlowLayout.RIGHT, 0, 0));
        closePanel.setPreferredSize(new Dimension(WIDTH, CLOSE_BUTTON_SIZE + 5));

        JLabel closeButton = new JLabel("×");
        closeButton.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 16));
        closeButton.setForeground(new Color(180, 180, 180));
        closeButton.setHorizontalAlignment(SwingConstants.CENTER);
        closeButton.setVerticalAlignment(SwingConstants.CENTER);
        closeButton.setPreferredSize(new Dimension(CLOSE_BUTTON_SIZE, CLOSE_BUTTON_SIZE));
        closeButton.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));

        // Hover effect
        closeButton.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseEntered(MouseEvent e) {
                closeButton.setForeground(new Color(255, 100, 100));
            }

            @Override
            public void mouseExited(MouseEvent e) {
                closeButton.setForeground(new Color(180, 180, 180));
            }

            @Override
            public void mouseClicked(MouseEvent e) {
                // Stop the application
                System.exit(0);
            }
        });

        closePanel.add(closeButton);
        return closePanel;
    }

    private SplashScreen() {
        BufferedImage logo = ImageUtil.loadImageResource(SplashScreen.class, "microbot_splash.png");

        setTitle("Microbot");
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        setUndecorated(true);
        setIconImages(Arrays.asList(ClientUI.ICON_128, ClientUI.ICON_16));

        // Rounded window
        getRootPane().setBorder(BorderFactory.createLineBorder(new Color(35, 35, 35)));
        setBackground(new Color(0, 0, 0, 0)); // allow shaping
        addComponentListener(new java.awt.event.ComponentAdapter() {
            @Override
            public void componentResized(java.awt.event.ComponentEvent e) {
                setShape(new RoundRectangle2D.Double(0, 0, getWidth(), getHeight(), ARC, ARC));
            }
        });

        JPanel root = new JPanel(new GridBagLayout());
        root.setBorder(new EmptyBorder(PAD, PAD, PAD, PAD));
        root.setBackground(ColorScheme.DARKER_GRAY_COLOR);
        setContentPane(root);

        final Font titleFont = new Font(Font.SANS_SERIF, Font.BOLD, 16);
        final Font bodyFont = new Font(Font.SANS_SERIF, Font.PLAIN, 13);
        final Color fg = new Color(230, 230, 230);
        final Color fgMuted = new Color(180, 180, 180);

        GridBagConstraints gc = new GridBagConstraints();
        gc.gridx = 0;
        gc.gridy = 0;
        gc.weightx = 1;
        gc.fill = GridBagConstraints.HORIZONTAL;

        // Close button
        gc.insets = new Insets(0, 0, PAD / 2, 0);
        root.add(createCloseButton(), gc);

        // Logo
        JLabel logoLabel = new JLabel(new ImageIcon(logo));
        logoLabel.setHorizontalAlignment(SwingConstants.CENTER);
        gc.gridy++;
        gc.insets = new Insets(0, 0, PAD, 0);
        root.add(logoLabel, gc);

        // Primary action
        action.setHorizontalAlignment(SwingConstants.CENTER);
        action.setFont(titleFont);
        action.setForeground(fg);
        gc.gridy++;
        gc.insets = new Insets(0, 0, 6, 0);
        root.add(action, gc);

        // Sub action
        subAction.setHorizontalAlignment(SwingConstants.CENTER);
        subAction.setFont(bodyFont);
        subAction.setForeground(fgMuted);
        gc.gridy++;
        gc.insets = new Insets(0, 0, PAD, 0);
        root.add(subAction, gc);

        // Progress
        progress.setMaximum(1000);
        progress.setStringPainted(false);
        progress.setBorderPainted(false);
        progress.setFont(bodyFont);
        progress.setBackground(new Color(40, 40, 40));
        progress.setForeground(ColorScheme.BRAND_ORANGE);
        progress.setUI(new BasicProgressBarUI() {
            @Override
            protected void paintDeterminate(Graphics g, JComponent c) {
                int w = c.getWidth(), h = c.getHeight();
                Graphics2D g2 = (Graphics2D) g.create();
                g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

                // Track
                g2.setColor(new Color(55, 55, 55));
                g2.fillRoundRect(0, 0, w, h, 10, 10);

                // Bar
                int pw = (int) Math.round(progress.getPercentComplete() * w);
                g2.setColor(ColorScheme.BRAND_ORANGE);
                g2.fillRoundRect(0, 0, Math.max(ARC, pw), h, 10, 10);

                // Text
                if (progress.isStringPainted()) {
                    String s = progress.getString();
                    FontMetrics fm = g2.getFontMetrics();
                    int tx = (w - fm.stringWidth(s)) / 2;
                    int ty = (h + fm.getAscent() - fm.getDescent()) / 2;
                    g2.setColor(Color.BLACK);
                    g2.drawString(s, tx + 1, ty + 1);
                    g2.setColor(Color.WHITE);
                    g2.drawString(s, tx, ty);
                }
                g2.dispose();
            }
        });
        gc.gridy++;
        gc.insets = new Insets(0, 0, PAD, 0);
        root.add(progress, gc);

        // Divider
        JSeparator sep = new JSeparator();
        sep.setForeground(new Color(70, 70, 70));
        sep.setBackground(new Color(70, 70, 70));
        gc.gridy++;
        gc.insets = new Insets(0, 0, PAD, 0);
        root.add(sep, gc);

        // Facts panel
        JPanel factCard = new JPanel(new BorderLayout());
        factCard.setOpaque(false);
        factCard.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(new Color(55, 55, 55)),
                new EmptyBorder(PAD, PAD, PAD, PAD)
        ));

        JLabel factTitle = new JLabel("💡 Did you know?");
        factTitle.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 13));
        factTitle.setForeground(fg);
        factTitle.setBorder(new EmptyBorder(0, 0, 8, 0));
        factCard.add(factTitle, BorderLayout.NORTH);

        // --- build facts panel ---
        // Replace the JTextPane section with this JTextArea approach
        JTextArea factArea = new JTextArea();
        factArea.setEditable(false);
        factArea.setFocusable(false);
        factArea.setOpaque(false);
        factArea.setLineWrap(true);
        factArea.setWrapStyleWord(true);
        factArea.setFont(bodyFont);
        factArea.setForeground(fgMuted);
        factArea.setBackground(new Color(0, 0, 0, 0));
        factArea.setText(getFact());
        factArea.setPreferredSize(new Dimension(WIDTH - (PAD * 4), 160));
        // Add the factPane directly to the card
        factCard.add(factArea, BorderLayout.CENTER);

        // updates
        pcs.addPropertyChangeListener("fact", evt -> SwingUtilities.invokeLater(() -> {
            factArea.setText(String.valueOf(evt.getNewValue()));
            factArea.setCaretPosition(0);
            factArea.revalidate();
            factCard.revalidate();
        }));

        // Proper GridBag constraints for the fact card
        gc.gridy++;
        gc.insets = new Insets(0, 0, 0, 0);
        gc.weighty = 1.0; // Allow vertical expansion
        gc.fill = GridBagConstraints.BOTH; // Fill both horizontal and vertical space
        root.add(factCard, gc);

        // Size and center
        setSize(WIDTH, 520);
        setMinimumSize(new Dimension(WIDTH, 420));
        setLocationRelativeTo(null);

        timer = new Timer(100, this);
        timer.setRepeats(true);
        timer.start();

        setVisible(true);

        ScheduledExecutorService scheduledRandomFactExecutorService = Executors.newSingleThreadScheduledExecutor();
        scheduledRandomFactFuture = scheduledRandomFactExecutorService.scheduleAtFixedRate(
                () -> {
                    RandomFactClient.getRandomFactAsync(SplashScreen::setFact);
                },
                0, 20, TimeUnit.SECONDS);
    }

    @Override
    public void actionPerformed(ActionEvent e) {
        action.setText(actionText);
        subAction.setText(subActionText);
        progress.setMaximum(1000);
        progress.setValue((int) (overallProgress * 1000));

        String progressText = this.progressText;
        if (progressText == null) {
            progress.setStringPainted(false);
        } else {
            progress.setStringPainted(true);
            progress.setString(progressText);
        }
    }

    public static boolean isOpen() {
        return INSTANCE != null;
    }

    public static void init() {
        try {
            SwingUtilities.invokeAndWait(() -> {
                if (INSTANCE != null) return;

                try {
                    boolean hasLAF = UIManager.getLookAndFeel() instanceof RuneLiteLAF;
                    if (!hasLAF) {
                        UIManager.setLookAndFeel(UIManager.getCrossPlatformLookAndFeelClassName());
                    }
                    INSTANCE = new SplashScreen();
                } catch (Exception e) {
                    log.warn("Unable to start splash screen", e);
                }
            });
        } catch (InterruptedException | InvocationTargetException bs) {
            throw new RuntimeException(bs);
        }
    }

    public static void stop() {
        SwingUtilities.invokeLater(() -> {
            if (INSTANCE == null) return;

            INSTANCE.timer.stop();
            if (scheduledRandomFactFuture != null) {
                scheduledRandomFactFuture.cancel(true);
            }
            if (scheduledRandomFactExecutorService != null) {
                scheduledRandomFactExecutorService.shutdownNow();
                scheduledRandomFactExecutorService = null;
            }
            INSTANCE.setDefaultCloseOperation(JFrame.DO_NOTHING_ON_CLOSE);
            INSTANCE.dispose();
            INSTANCE = null;
        });
    }

    public static void stage(double overallProgress, @Nullable String actionText, String subActionText) {
        stage(overallProgress, actionText, subActionText, null);
    }

    public static void stage(double startProgress, double endProgress,
                             @Nullable String actionText, String subActionText,
                             int done, int total, boolean mib) {
        String progress;
        if (mib) {
            final double MiB = 1024 * 1024;
            final double CEIL = 1.d / 10.d;
            progress = String.format("%.1f / %.1f MiB", done / MiB, (total / MiB) + CEIL);
        } else {
            progress = done + " / " + total;
        }
        stage(startProgress + ((endProgress - startProgress) * done / total), actionText, subActionText, progress);
    }

    public static void stage(double overallProgress, @Nullable String actionText, String subActionText, @Nullable String progressText) {
        if (INSTANCE != null) {
            INSTANCE.overallProgress = overallProgress;
            if (actionText != null) {
                INSTANCE.actionText = actionText;
            }
            INSTANCE.subActionText = subActionText;
            INSTANCE.progressText = progressText;
        }
    }
}