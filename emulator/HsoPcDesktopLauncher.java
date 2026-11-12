import java.awt.BasicStroke;
import java.awt.BorderLayout;
import java.awt.Color;
import java.awt.Component;
import java.awt.Cursor;
import java.awt.Dimension;
import java.awt.Font;
import java.awt.FontMetrics;
import java.awt.Graphics;
import java.awt.Graphics2D;
import java.awt.GridBagConstraints;
import java.awt.GridBagLayout;
import java.awt.GridLayout;
import java.awt.Insets;
import java.awt.RenderingHints;
import java.awt.event.KeyAdapter;
import java.awt.event.KeyEvent;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.awt.event.MouseMotionAdapter;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.io.PrintStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import javax.swing.BorderFactory;
import javax.swing.Box;
import javax.swing.BoxLayout;
import javax.swing.DefaultListCellRenderer;
import javax.swing.JButton;
import javax.swing.JCheckBox;
import javax.swing.JComboBox;
import javax.swing.JFrame;
import javax.swing.JLabel;
import javax.swing.JList;
import javax.swing.JPanel;
import javax.swing.JScrollPane;
import javax.swing.JTextArea;
import javax.swing.JTextField;
import javax.swing.SwingUtilities;
import javax.swing.Timer;
import javax.swing.UIManager;
import javax.swing.border.EmptyBorder;
import javax.swing.border.TitledBorder;

public class HsoPcDesktopLauncher {

    private static final int GAME_W = 240;
    private static final int GAME_H = 320;
    private static final int DISPLAY_W = 360;
    private static final int DISPLAY_H = 480;

    private static final String DEFAULT_CLOUD_URL = "https://j2meweb24-7.onrender.com";

    private static final String[] QUEST_SCOPE_CODES = new String[] {
        "all", "main", "sub", "script", "farm"
    };

    private static final String[] QUEST_SCOPE_LABELS = new String[] {
        "1. Tất cả NV (Chính + Phụ)",
        "2. Chỉ NV Chính Tuyến",
        "3. Chỉ NV Phụ / Hằng Ngày",
        "4. Kịch Bản Chuẩn Lv.1 -> 135",
        "5. Chỉ Cày Quái Theo Level"
    };

    private static JPanel canvasPanel;
    private static DarkButton btnToggleAuto;
    private static JComboBox<String> comboQuestType;
    private static JCheckBox chkAutoPotion;
    private static JCheckBox chkAutoBuyPotion;
    private static JCheckBox chkAutoSell;
    private static JTextField txtQuickUser;
    private static JTextField txtQuickPass;
    private static JTextField txtCloudUrl;
    private static JCheckBox chkCloudSync247;
    private static JLabel lblCloudSyncStatus;
    private static JTextArea txtStatusSummary;
    private static JTextArea txtLogConsole;
    private static volatile boolean syncingUi = false;
    private static File currentWorkDir = new File(".");
    private static final String PC_CLIENT_ID = "pc_" + Math.abs((System.getProperty("user.name", "player") + "_" + System.getProperty("os.name", "win")).hashCode());

    /**
     * Nút bấm vẽ trực tiếp bằng Graphics2D để không bao giờ bị nền trắng chữ trắng trên Windows 10/11.
     */
    private static final class DarkButton extends JButton {
        private Color baseBg;
        private Color textCol = Color.WHITE;
        private boolean hovered = false;
        private boolean pressed = false;

        DarkButton(String text, Color bg) {
            super(text);
            this.baseBg = bg;
            setFont(new Font("SansSerif", Font.BOLD, 12));
            setForeground(Color.WHITE);
            setFocusPainted(false);
            setBorderPainted(false);
            setContentAreaFilled(false);
            setOpaque(false);
            setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));
            setMargin(new Insets(6, 10, 6, 10));

            addMouseListener(new MouseAdapter() {
                @Override
                public void mouseEntered(MouseEvent e) {
                    hovered = true;
                    repaint();
                }

                @Override
                public void mouseExited(MouseEvent e) {
                    hovered = false;
                    pressed = false;
                    repaint();
                }

                @Override
                public void mousePressed(MouseEvent e) {
                    pressed = true;
                    repaint();
                }

                @Override
                public void mouseReleased(MouseEvent e) {
                    pressed = false;
                    repaint();
                }
            });
        }

        void setButtonColor(Color bg) {
            this.baseBg = bg;
            repaint();
        }

        @Override
        protected void paintComponent(Graphics g) {
            Graphics2D g2 = (Graphics2D) g.create();
            g2.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            g2.setRenderingHint(RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);

            int w = getWidth();
            int h = getHeight();

            Color fill = baseBg != null ? baseBg : new Color(37, 99, 235);
            if (pressed) {
                fill = darker(fill, 0.80f);
            } else if (hovered) {
                fill = brighter(fill, 1.15f);
            }

            g2.setColor(fill);
            g2.fillRoundRect(0, 0, w - 1, h - 1, 10, 10);

            g2.setColor(brighter(fill, 1.28f));
            g2.setStroke(new BasicStroke(1.2f));
            g2.drawRoundRect(0, 0, w - 1, h - 1, 10, 10);

            g2.setFont(getFont());
            FontMetrics fm = g2.getFontMetrics();
            String txt = getText() != null ? getText() : "";
            int tx = Math.max(4, (w - fm.stringWidth(txt)) / 2);
            int ty = (h - fm.getHeight()) / 2 + fm.getAscent();

            g2.setColor(new Color(0, 0, 0, 140));
            g2.drawString(txt, tx + 1, ty + 1);

            g2.setColor(textCol);
            g2.drawString(txt, tx, ty);
            g2.dispose();
        }

        private static Color brighter(Color c, float factor) {
            int r = Math.min(255, (int) (c.getRed() * factor));
            int g = Math.min(255, (int) (c.getGreen() * factor));
            int b = Math.min(255, (int) (c.getBlue() * factor));
            return new Color(r, g, b);
        }

        private static Color darker(Color c, float factor) {
            int r = Math.max(0, (int) (c.getRed() * factor));
            int g = Math.max(0, (int) (c.getGreen() * factor));
            int b = Math.max(0, (int) (c.getBlue() * factor));
            return new Color(r, g, b);
        }
    }

    public static void main(String[] args) {
        if (args.length >= 3 && java.awt.GraphicsEnvironment.isHeadless()) {
            HeadlessJ2ME.main(args);
            return;
        }

        HeadlessJ2ME.setPreventSystemExit(true);

        try {
            UIManager.setLookAndFeel(UIManager.getCrossPlatformLookAndFeelClassName());
        } catch (Throwable ignored) {}

        currentWorkDir = resolveWritableWorkDir();
        try {
            System.setProperty("user.dir", currentWorkDir.getAbsolutePath());
        } catch (Throwable ignored) {}
        File jarFile = prepareEmbeddedResources(currentWorkDir);

        hookStreamsToGuiConsole();

        SwingUtilities.invokeLater(() -> createAndShowGui(jarFile));

        Thread vmThread = new Thread(() -> {
            try {
                HeadlessJ2ME.setPreventSystemExit(true);
                HeadlessJ2ME.setRenderStreamingEnabled(false);
                HeadlessJ2ME.main(new String[] {
                    jarFile.getAbsolutePath(),
                    String.valueOf(GAME_W),
                    String.valueOf(GAME_H),
                    "30"
                });
            } catch (Throwable t) {
                t.printStackTrace(System.err);
            }
        }, "HSO-PC-VM-Main");
        vmThread.setDaemon(true);
        vmThread.start();

        // Nạp sẵn kịch bản 38 bước Lv.1 -> Lv.135 ngay sau khi khởi động
        new Thread(() -> {
            try {
                Thread.sleep(1500);
                HeadlessJ2ME.dispatchCommand("AUTO_QUEST:script:load");
            } catch (Throwable ignored) {}
        }, "HSO-PC-InitScript").start();

        // Khởi chạy luồng Mạng Kép & Đồng bộ 24/7 với Cloud Render (https://j2meweb24-7.onrender.com)
        startCloud247MaintainLoop();
    }

    private static File resolveWritableWorkDir() {
        try {
            File codeSourceFile = new File(HsoPcDesktopLauncher.class.getProtectionDomain().getCodeSource().getLocation().toURI());
            File jarDir = codeSourceFile.isDirectory() ? codeSourceFile : codeSourceFile.getParentFile();
            if (isUsableWorkDir(jarDir)) {
                return jarDir;
            }
        } catch (Throwable ignored) {}

        File userDir = new File(System.getProperty("user.dir", ".")).getAbsoluteFile();
        if (isUsableWorkDir(userDir)) {
            return userDir;
        }

        File homeFallback = new File(System.getProperty("user.home", System.getProperty("java.io.tmpdir", ".")), "HSO-DuyTruong-PC");
        homeFallback.mkdirs();
        if (isUsableWorkDir(homeFallback)) {
            return homeFallback;
        }

        File tmpFallback = new File(System.getProperty("java.io.tmpdir", "."), "HSO-DuyTruong-PC");
        tmpFallback.mkdirs();
        return tmpFallback;
    }

    private static boolean isUsableWorkDir(File dir) {
        if (dir == null) return false;
        try {
            String lower = dir.getAbsolutePath().toLowerCase();
            if (lower.contains("\\windows\\system32") || lower.contains("\\windows\\syswow64")) {
                return false;
            }
            if (!dir.exists() && !dir.mkdirs()) {
                return false;
            }
            File probe = new File(dir, ".hso_write_test_" + System.nanoTime());
            try (FileOutputStream fos = new FileOutputStream(probe)) {
                fos.write(1);
            }
            probe.delete();
            return true;
        } catch (Throwable t) {
            return false;
        }
    }

    private static File prepareEmbeddedResources(File workDir) {
        File duyTruongJar = new File(workDir, "HSO-402-VIP-DuyTruong.jar");
        File legacyJar = new File(workDir, "HSO-402-VIP-0.jar");

        extractResourceOverwrite("/embedded/HSO-402-VIP-0.jar", legacyJar);
        boolean ok = extractResourceOverwrite("/embedded/HSO-402-VIP-0.jar", duyTruongJar);
        if (!ok && (!duyTruongJar.exists() || duyTruongJar.length() < 100000)) {
            duyTruongJar = new File(workDir, "HSO-402-VIP-DuyTruong-" + System.currentTimeMillis() + ".jar");
            extractResourceOverwrite("/embedded/HSO-402-VIP-0.jar", duyTruongJar);
        }

        extractResourceOverwrite("/embedded/aq_quest_script.json", new File(workDir, "aq_quest_script.json"));
        extractResourceOverwrite("/embedded/aq_quest_script.json", new File(workDir, "saves/rms_admin/aq_quest_script.json"));
        extractResourceOverwrite("/embedded/aq_world_knowledge.json", new File(workDir, "aq_world_knowledge.json"));
        extractResourceOverwrite("/embedded/aq_world_knowledge.json", new File(workDir, "saves/rms_admin/aq_world_knowledge.json"));

        if (duyTruongJar.exists() && duyTruongJar.length() > 100000) {
            return duyTruongJar;
        }
        if (legacyJar.exists() && legacyJar.length() > 100000) {
            return legacyJar;
        }

        File tmpDir = new File(System.getProperty("java.io.tmpdir", "."), "HSO-DuyTruong-PC");
        tmpDir.mkdirs();
        File tmpJar = new File(tmpDir, "HSO-402-VIP-DuyTruong.jar");
        extractResourceOverwrite("/embedded/HSO-402-VIP-0.jar", tmpJar);
        return tmpJar;
    }

    private static boolean extractResourceOverwrite(String resourcePath, File targetFile) {
        try (InputStream in = HsoPcDesktopLauncher.class.getResourceAsStream(resourcePath)) {
            if (in == null) return false;
            File parent = targetFile.getParentFile();
            if (parent != null && !parent.exists()) {
                parent.mkdirs();
            }
            try (FileOutputStream out = new FileOutputStream(targetFile)) {
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) {
                    out.write(buf, 0, n);
                }
            }
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static void createAndShowGui(File jarFile) {
        Color bgDark = new Color(15, 23, 42);
        Color cardDark = new Color(30, 41, 59);
        Color borderCol = new Color(71, 85, 105);
        Color textLight = new Color(241, 245, 249);
        Color accentCyan = new Color(56, 189, 248);

        JFrame frame = new JFrame("Hiệp Sĩ Online 402 VIP ADMIN - Duy Trường (Bản PC Đồng Bộ Mạng Kép 24/7)");
        frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
        frame.getContentPane().setBackground(bgDark);
        frame.setLayout(new BorderLayout(12, 12));
        ((JPanel) frame.getContentPane()).setBorder(new EmptyBorder(10, 10, 10, 10));

        // ================= LEFT: GAME SCREEN + KEYPAD =================
        JPanel leftContainer = new JPanel(new BorderLayout(0, 8));
        leftContainer.setBackground(bgDark);

        JPanel screenWrapper = new JPanel(new GridBagLayout());
        screenWrapper.setBackground(cardDark);
        screenWrapper.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(borderCol, 1),
                new EmptyBorder(8, 8, 8, 8)
        ));

        canvasPanel = new JPanel() {
            @Override
            protected void paintComponent(Graphics g) {
                super.paintComponent(g);
                Graphics2D g2 = (Graphics2D) g;
                g2.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
                BufferedImage lcd = HeadlessJ2ME.getCurrentLcdFrame();
                if (lcd != null) {
                    g2.drawImage(lcd, 0, 0, getWidth(), getHeight(), null);
                } else {
                    g2.setColor(new Color(15, 23, 42));
                    g2.fillRect(0, 0, getWidth(), getHeight());
                    g2.setColor(accentCyan);
                    g2.setFont(new Font("SansSerif", Font.BOLD, 14));
                    g2.drawString("Đang khởi động HSO 402 VIP ADMIN - Duy Trường...", 16, getHeight() / 2);
                }
            }
        };
        canvasPanel.setPreferredSize(new Dimension(DISPLAY_W, DISPLAY_H));
        canvasPanel.setMinimumSize(new Dimension(DISPLAY_W, DISPLAY_H));
        canvasPanel.setFocusable(true);
        canvasPanel.setFocusTraversalKeysEnabled(false);
        canvasPanel.setCursor(Cursor.getPredefinedCursor(Cursor.HAND_CURSOR));

        canvasPanel.addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                canvasPanel.requestFocusInWindow();
                int gx = Math.max(0, Math.min(GAME_W - 1, e.getX() * GAME_W / Math.max(1, canvasPanel.getWidth())));
                int gy = Math.max(0, Math.min(GAME_H - 1, e.getY() * GAME_H / Math.max(1, canvasPanel.getHeight())));
                HeadlessJ2ME.dispatchCommand("MD:" + gx + "," + gy);
                canvasPanel.repaint();
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                int gx = Math.max(0, Math.min(GAME_W - 1, e.getX() * GAME_W / Math.max(1, canvasPanel.getWidth())));
                int gy = Math.max(0, Math.min(GAME_H - 1, e.getY() * GAME_H / Math.max(1, canvasPanel.getHeight())));
                HeadlessJ2ME.dispatchCommand("MU:" + gx + "," + gy);
                canvasPanel.repaint();
            }
        });

        canvasPanel.addMouseMotionListener(new MouseMotionAdapter() {
            @Override
            public void mouseDragged(MouseEvent e) {
                int gx = Math.max(0, Math.min(GAME_W - 1, e.getX() * GAME_W / Math.max(1, canvasPanel.getWidth())));
                int gy = Math.max(0, Math.min(GAME_H - 1, e.getY() * GAME_H / Math.max(1, canvasPanel.getHeight())));
                HeadlessJ2ME.dispatchCommand("MM:" + gx + "," + gy);
                canvasPanel.repaint();
            }
        });

        canvasPanel.addKeyListener(new KeyAdapter() {
            @Override
            public void keyPressed(KeyEvent e) {
                if (e.getKeyCode() == KeyEvent.VK_TAB) {
                    HeadlessJ2ME.dispatchCommand("SWITCH_LOGIN_FIELD");
                    e.consume();
                    canvasPanel.repaint();
                    return;
                }
                boolean tfieldActive = HeadlessJ2ME.isAnyTFieldFocused();
                int j2meKey = mapPcKeyToJ2me(e, tfieldActive);
                if (j2meKey != Integer.MIN_VALUE) {
                    HeadlessJ2ME.dispatchCommand("KD:" + j2meKey);
                    canvasPanel.repaint();
                }
            }

            @Override
            public void keyReleased(KeyEvent e) {
                if (e.getKeyCode() == KeyEvent.VK_TAB) {
                    e.consume();
                    return;
                }
                boolean tfieldActive = HeadlessJ2ME.isAnyTFieldFocused();
                int j2meKey = mapPcKeyToJ2me(e, tfieldActive);
                if (j2meKey != Integer.MIN_VALUE) {
                    HeadlessJ2ME.dispatchCommand("KU:" + j2meKey);
                    canvasPanel.repaint();
                }
            }

            @Override
            public void keyTyped(KeyEvent e) {
                char ch = e.getKeyChar();
                if (ch >= 32 && ch <= 126 && HeadlessJ2ME.isAnyTFieldFocused()) {
                    HeadlessJ2ME.dispatchCommand("KP:" + (int) ch);
                    canvasPanel.repaint();
                }
            }
        });

        screenWrapper.add(canvasPanel);
        leftContainer.add(screenWrapper, BorderLayout.CENTER);
        leftContainer.add(buildKeypadPanel(cardDark, borderCol), BorderLayout.SOUTH);

        // ================= RIGHT: AUTO QUEST, CLOUD 24/7 & VIP CONTROL PANEL =================
        JPanel rightContainer = new JPanel();
        rightContainer.setLayout(new BoxLayout(rightContainer, BoxLayout.Y_AXIS));
        rightContainer.setBackground(bgDark);
        rightContainer.setPreferredSize(new Dimension(460, 740));

        // 0. Panel Đăng Nhập Nhanh & Chọn Dòng Tài Khoản / Mật Khẩu
        JPanel loginCard = createStyledCard("ĐĂNG NHẬP NHANH & CHỌN Ô TÀI KHOẢN / MẬT KHẨU", cardDark, borderCol, accentCyan);
        loginCard.setLayout(new GridBagLayout());
        GridBagConstraints lgbc = new GridBagConstraints();
        lgbc.insets = new Insets(2, 4, 2, 4);
        lgbc.fill = GridBagConstraints.HORIZONTAL;

        lgbc.gridx = 0;
        lgbc.gridy = 0;
        lgbc.weightx = 0.22;
        JLabel lblUser = new JLabel("Tài khoản:");
        lblUser.setForeground(textLight);
        lblUser.setFont(new Font("SansSerif", Font.BOLD, 12));
        loginCard.add(lblUser, lgbc);

        lgbc.gridx = 1;
        lgbc.weightx = 0.48;
        txtQuickUser = new JTextField();
        txtQuickUser.setFont(new Font("SansSerif", Font.BOLD, 12));
        txtQuickUser.setBackground(new Color(15, 23, 42));
        txtQuickUser.setForeground(Color.WHITE);
        txtQuickUser.setCaretColor(Color.WHITE);
        txtQuickUser.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(borderCol, 1),
                new EmptyBorder(4, 6, 4, 6)
        ));
        loginCard.add(txtQuickUser, lgbc);

        lgbc.gridx = 2;
        lgbc.weightx = 0.30;
        DarkButton btnFocusUser = new DarkButton("Chọn Dòng TK", new Color(37, 99, 235));
        btnFocusUser.setPreferredSize(new Dimension(115, 26));
        btnFocusUser.addActionListener(e -> {
            String u = txtQuickUser.getText().trim();
            if (!u.isEmpty()) {
                HeadlessJ2ME.loginWithCredentials(u, null, false);
            }
            HeadlessJ2ME.focusLoginField(false);
            canvasPanel.requestFocusInWindow();
            canvasPanel.repaint();
        });
        loginCard.add(btnFocusUser, lgbc);

        lgbc.gridx = 0;
        lgbc.gridy = 1;
        lgbc.weightx = 0.22;
        JLabel lblPass = new JLabel("Mật khẩu:");
        lblPass.setForeground(textLight);
        lblPass.setFont(new Font("SansSerif", Font.BOLD, 12));
        loginCard.add(lblPass, lgbc);

        lgbc.gridx = 1;
        lgbc.weightx = 0.48;
        txtQuickPass = new JTextField();
        txtQuickPass.setFont(new Font("SansSerif", Font.BOLD, 12));
        txtQuickPass.setBackground(new Color(15, 23, 42));
        txtQuickPass.setForeground(Color.WHITE);
        txtQuickPass.setCaretColor(Color.WHITE);
        txtQuickPass.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(borderCol, 1),
                new EmptyBorder(4, 6, 4, 6)
        ));
        loginCard.add(txtQuickPass, lgbc);

        lgbc.gridx = 2;
        lgbc.weightx = 0.30;
        DarkButton btnFocusPass = new DarkButton("Chọn Dòng MK", new Color(124, 58, 237));
        btnFocusPass.setPreferredSize(new Dimension(115, 26));
        btnFocusPass.addActionListener(e -> {
            String p = txtQuickPass.getText().trim();
            if (!p.isEmpty()) {
                HeadlessJ2ME.loginWithCredentials(null, p, false);
            }
            HeadlessJ2ME.focusLoginField(true);
            canvasPanel.requestFocusInWindow();
            canvasPanel.repaint();
        });
        loginCard.add(btnFocusPass, lgbc);

        lgbc.gridx = 0;
        lgbc.gridy = 2;
        lgbc.gridwidth = 3;
        lgbc.weightx = 1.0;
        JPanel loginBtnRow = new JPanel(new GridLayout(1, 2, 6, 0));
        loginBtnRow.setBackground(cardDark);

        DarkButton btnFillOnly = new DarkButton("Điền TK + MK Vào Game", new Color(14, 116, 144));
        btnFillOnly.setPreferredSize(new Dimension(180, 28));
        btnFillOnly.addActionListener(e -> {
            HeadlessJ2ME.loginWithCredentials(txtQuickUser.getText().trim(), txtQuickPass.getText().trim(), false);
            canvasPanel.requestFocusInWindow();
            canvasPanel.repaint();
        });

        DarkButton btnFillAndLogin = new DarkButton("▶ Điền & Đăng Nhập Ngay", new Color(22, 163, 74));
        btnFillAndLogin.setPreferredSize(new Dimension(180, 28));
        btnFillAndLogin.addActionListener(e -> {
            HeadlessJ2ME.loginWithCredentials(txtQuickUser.getText().trim(), txtQuickPass.getText().trim(), true);
            syncWithCloudServerAsync(false);
            canvasPanel.requestFocusInWindow();
            canvasPanel.repaint();
        });

        loginBtnRow.add(btnFillOnly);
        loginBtnRow.add(btnFillAndLogin);
        loginCard.add(loginBtnRow, lgbc);

        rightContainer.add(loginCard);
        rightContainer.add(Box.createVerticalStrut(6));

        // 0.5. Panel Đồng Bộ Mạng Kép & Duy Trì 24/7 Trên Web Public (https://j2meweb24-7.onrender.com)
        JPanel cloudCard = createStyledCard("MẠNG KÉP & DUY TRÌ 24/7 (j2meweb24-7.onrender.com)", cardDark, borderCol, new Color(52, 211, 153));
        cloudCard.setLayout(new GridBagLayout());
        GridBagConstraints cgbc = new GridBagConstraints();
        cgbc.insets = new Insets(2, 4, 2, 4);
        cgbc.fill = GridBagConstraints.HORIZONTAL;
        cgbc.gridx = 0;
        cgbc.gridy = 0;
        cgbc.weightx = 0.25;

        JLabel lblCloud = new JLabel("Server 24/7:");
        lblCloud.setForeground(textLight);
        lblCloud.setFont(new Font("SansSerif", Font.BOLD, 11));
        cloudCard.add(lblCloud, cgbc);

        cgbc.gridx = 1;
        cgbc.weightx = 0.75;
        txtCloudUrl = new JTextField(DEFAULT_CLOUD_URL);
        txtCloudUrl.setFont(new Font("SansSerif", Font.BOLD, 11));
        txtCloudUrl.setBackground(new Color(15, 23, 42));
        txtCloudUrl.setForeground(new Color(56, 189, 248));
        txtCloudUrl.setCaretColor(Color.WHITE);
        txtCloudUrl.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(borderCol, 1),
                new EmptyBorder(3, 6, 3, 6)
        ));
        cloudCard.add(txtCloudUrl, cgbc);

        cgbc.gridx = 0;
        cgbc.gridy = 1;
        cgbc.gridwidth = 2;
        chkCloudSync247 = createStyledCheck("Tự động duy trì Mạng Kép & giữ thức Server 24/7", true, cardDark, textLight);
        cloudCard.add(chkCloudSync247, cgbc);

        cgbc.gridy = 2;
        JPanel cloudBtnRow = new JPanel(new GridLayout(1, 2, 6, 0));
        cloudBtnRow.setBackground(cardDark);

        DarkButton btnSyncNow = new DarkButton("Đồng Bộ Save Lên Cloud", new Color(14, 116, 144));
        btnSyncNow.setPreferredSize(new Dimension(180, 28));
        btnSyncNow.addActionListener(e -> {
            syncWithCloudServerAsync(false);
            canvasPanel.requestFocusInWindow();
        });

        DarkButton btnHandover247 = new DarkButton("Uỷ Thác Cloud Treo 24/7", new Color(16, 185, 129));
        btnHandover247.setPreferredSize(new Dimension(180, 28));
        btnHandover247.addActionListener(e -> {
            syncWithCloudServerAsync(true);
            canvasPanel.requestFocusInWindow();
        });

        cloudBtnRow.add(btnSyncNow);
        cloudBtnRow.add(btnHandover247);
        cloudCard.add(cloudBtnRow, cgbc);

        cgbc.gridy = 3;
        lblCloudSyncStatus = new JLabel("Đang kết nối Mạng Kép tới " + DEFAULT_CLOUD_URL + "...");
        lblCloudSyncStatus.setForeground(new Color(167, 243, 208));
        lblCloudSyncStatus.setFont(new Font("SansSerif", Font.PLAIN, 11));
        cloudCard.add(lblCloudSyncStatus, cgbc);

        rightContainer.add(cloudCard);
        rightContainer.add(Box.createVerticalStrut(6));

        // 1. Panel Điều Khiển Auto Làm Nhiệm Vụ
        JPanel aqCard = createStyledCard("TỰ ĐỘNG LÀM NHIỆM VỤ & CHỌN LOẠI NV (DUY TRƯỜNG)", cardDark, borderCol, accentCyan);
        aqCard.setLayout(new GridBagLayout());
        GridBagConstraints gbc = new GridBagConstraints();
        gbc.insets = new Insets(3, 5, 3, 5);
        gbc.fill = GridBagConstraints.HORIZONTAL;
        gbc.weightx = 1.0;
        gbc.gridx = 0;
        gbc.gridy = 0;
        gbc.gridwidth = 2;

        btnToggleAuto = new DarkButton("▶ BẬT AUTO LÀM NHIỆM VỤ", new Color(22, 163, 74));
        btnToggleAuto.setFont(new Font("SansSerif", Font.BOLD, 13));
        btnToggleAuto.setPreferredSize(new Dimension(390, 34));
        btnToggleAuto.addActionListener(e -> {
            boolean nextState = !HeadlessJ2ME.isAutoQuestActive();
            sendAutoQuestFromUi(nextState ? "1" : "0");
            syncWithCloudServerAsync(false);
            canvasPanel.requestFocusInWindow();
        });
        aqCard.add(btnToggleAuto, gbc);

        gbc.gridy++;
        gbc.gridwidth = 1;
        gbc.weightx = 0.32;
        JLabel lblScope = new JLabel("Loại Nhiệm Vụ:");
        lblScope.setForeground(textLight);
        lblScope.setFont(new Font("SansSerif", Font.BOLD, 12));
        aqCard.add(lblScope, gbc);

        gbc.gridx = 1;
        gbc.weightx = 0.68;
        comboQuestType = new JComboBox<>(QUEST_SCOPE_LABELS);
        comboQuestType.setFont(new Font("SansSerif", Font.BOLD, 12));
        comboQuestType.setBackground(new Color(15, 23, 42));
        comboQuestType.setForeground(Color.WHITE);
        comboQuestType.setRenderer(new DefaultListCellRenderer() {
            @Override
            public Component getListCellRendererComponent(JList<?> list, Object value, int index, boolean isSelected, boolean cellHasFocus) {
                JLabel lbl = (JLabel) super.getListCellRendererComponent(list, value, index, isSelected, cellHasFocus);
                lbl.setFont(new Font("SansSerif", Font.BOLD, 12));
                lbl.setBorder(new EmptyBorder(4, 8, 4, 8));
                if (isSelected) {
                    lbl.setBackground(new Color(37, 99, 235));
                    lbl.setForeground(Color.WHITE);
                } else {
                    lbl.setBackground(new Color(15, 23, 42));
                    lbl.setForeground(new Color(241, 245, 249));
                }
                return lbl;
            }
        });
        comboQuestType.addActionListener(e -> {
            if (syncingUi) return;
            String mode = HeadlessJ2ME.isAutoQuestActive() ? "1" : "0";
            sendAutoQuestFromUi(mode);
            canvasPanel.requestFocusInWindow();
        });
        aqCard.add(comboQuestType, gbc);

        gbc.gridy++;
        gbc.gridx = 0;
        gbc.gridwidth = 2;
        chkAutoPotion = createStyledCheck("Tự động dùng bình HP / MP khi đánh quái", true, cardDark, textLight);
        chkAutoPotion.addActionListener(e -> {
            if (!syncingUi) sendAutoQuestFromUi(HeadlessJ2ME.isAutoQuestActive() ? "1" : "0");
        });
        aqCard.add(chkAutoPotion, gbc);

        gbc.gridy++;
        chkAutoBuyPotion = createStyledCheck("Tự động về NPC Lisa/Zulu mua thêm HP/MP khi cạn", true, cardDark, textLight);
        chkAutoBuyPotion.addActionListener(e -> {
            if (!syncingUi) sendAutoQuestFromUi(HeadlessJ2ME.isAutoQuestActive() ? "1" : "0");
        });
        aqCard.add(chkAutoBuyPotion, gbc);

        gbc.gridy++;
        chkAutoSell = createStyledCheck("Tự động bán trang bị rác (Trắng/Xanh/Vàng) khi đầy túi", true, cardDark, textLight);
        chkAutoSell.addActionListener(e -> {
            if (!syncingUi) {
                HeadlessJ2ME.dispatchCommand("AUTO_SELL:" + (chkAutoSell.isSelected() ? "1" : "0") + ":1:1:1:0:0:1:1:0:40");
            }
        });
        aqCard.add(chkAutoSell, gbc);

        gbc.gridy++;
        JPanel inGameMenuRow = new JPanel(new GridLayout(2, 2, 6, 5));
        inGameMenuRow.setBackground(cardDark);

        DarkButton btnOpenInGameAq = new DarkButton("Menu Auto NV (Trong Game)", new Color(37, 99, 235));
        btnOpenInGameAq.setPreferredSize(new Dimension(185, 28));
        btnOpenInGameAq.addActionListener(e -> {
            HeadlessJ2ME.openInGameAutoQuestMenu();
            canvasPanel.requestFocusInWindow();
        });

        DarkButton btnOpenInGameType = new DarkButton("Chọn Loại NV (Trong Game)", new Color(124, 58, 237));
        btnOpenInGameType.setPreferredSize(new Dimension(185, 28));
        btnOpenInGameType.addActionListener(e -> {
            HeadlessJ2ME.openInGameQuestTypeMenu();
            canvasPanel.requestFocusInWindow();
        });

        DarkButton btnStepOnce = new DarkButton("Chạy Ngay 1 Bước NV", new Color(14, 116, 144));
        btnStepOnce.setPreferredSize(new Dimension(185, 28));
        btnStepOnce.addActionListener(e -> {
            sendAutoQuestFromUi("step");
            canvasPanel.requestFocusInWindow();
        });

        DarkButton btnOpenVipMod = new DarkButton("Mở Menu VIP ADMIN", new Color(217, 119, 6));
        btnOpenVipMod.setPreferredSize(new Dimension(185, 28));
        btnOpenVipMod.addActionListener(e -> {
            HeadlessJ2ME.openInGameVipModMenu();
            canvasPanel.requestFocusInWindow();
        });

        inGameMenuRow.add(btnOpenInGameAq);
        inGameMenuRow.add(btnOpenInGameType);
        inGameMenuRow.add(btnStepOnce);
        inGameMenuRow.add(btnOpenVipMod);
        aqCard.add(inGameMenuRow, gbc);

        gbc.gridy++;
        JPanel extraRow = new JPanel(new GridLayout(1, 2, 6, 5));
        extraRow.setBackground(cardDark);
        DarkButton btnSellNow = new DarkButton("Bán Đồ Đầy Túi Ngay", new Color(190, 24, 93));
        btnSellNow.setPreferredSize(new Dimension(185, 28));
        btnSellNow.addActionListener(e -> {
            HeadlessJ2ME.dispatchCommand("AUTO_SELL:now");
            canvasPanel.requestFocusInWindow();
        });
        DarkButton btnDiag = new DarkButton("Chẩn Đoán Tiến Độ NV", new Color(51, 65, 85));
        btnDiag.setPreferredSize(new Dimension(185, 28));
        btnDiag.addActionListener(e -> {
            HeadlessJ2ME.dispatchCommand("AUTO_QUEST:diag");
            canvasPanel.requestFocusInWindow();
        });
        extraRow.add(btnSellNow);
        extraRow.add(btnDiag);
        aqCard.add(extraRow, gbc);

        rightContainer.add(aqCard);
        rightContainer.add(Box.createVerticalStrut(6));

        // 2. Panel Trạng Thái Thời Gian Thực
        JPanel statusCard = createStyledCard("TRẠNG THÁI & TIẾN ĐỘ NHIỆM VỤ", cardDark, borderCol, accentCyan);
        statusCard.setLayout(new BorderLayout());
        txtStatusSummary = new JTextArea(5, 32);
        txtStatusSummary.setEditable(false);
        txtStatusSummary.setFont(new Font("Monospaced", Font.PLAIN, 11));
        txtStatusSummary.setBackground(new Color(15, 23, 42));
        txtStatusSummary.setForeground(new Color(167, 243, 208));
        txtStatusSummary.setBorder(new EmptyBorder(4, 6, 4, 6));
        txtStatusSummary.setText("Đang đồng bộ trạng thái từ Engine...");
        statusCard.add(txtStatusSummary, BorderLayout.CENTER);
        rightContainer.add(statusCard);
        rightContainer.add(Box.createVerticalStrut(6));

        // 3. Panel Nhật Ký Hệ Thống (Log Console)
        JPanel logCard = createStyledCard("NHẬT KÝ AUTO QUEST & HỆ THỐNG", cardDark, borderCol, accentCyan);
        logCard.setLayout(new BorderLayout());
        txtLogConsole = new JTextArea(6, 32);
        txtLogConsole.setEditable(false);
        txtLogConsole.setLineWrap(true);
        txtLogConsole.setWrapStyleWord(true);
        txtLogConsole.setFont(new Font("Monospaced", Font.PLAIN, 11));
        txtLogConsole.setBackground(new Color(9, 14, 26));
        txtLogConsole.setForeground(new Color(226, 232, 240));
        JScrollPane logScroll = new JScrollPane(txtLogConsole);
        logScroll.setBorder(BorderFactory.createEmptyBorder());
        logCard.add(logScroll, BorderLayout.CENTER);
        rightContainer.add(logCard);

        frame.add(leftContainer, BorderLayout.WEST);
        frame.add(rightContainer, BorderLayout.CENTER);

        frame.pack();
        frame.setLocationRelativeTo(null);
        frame.setVisible(true);
        canvasPanel.requestFocusInWindow();

        Timer repaintTimer = new Timer(33, e -> canvasPanel.repaint());
        repaintTimer.start();

        Timer statusTimer = new Timer(600, e -> refreshUiFromEngine());
        statusTimer.start();
    }

    /**
     * Luồng duy trì Mạng Kép 24/7 từ Bản Máy Tính (PC) lên https://j2meweb24-7.onrender.com
     * Vừa giữ thức Server Render 24/7, vừa đồng bộ Save RMS & phiên người chơi PC lên trang Admin.
     */
    private static void startCloud247MaintainLoop() {
        Thread t = new Thread(() -> {
            try {
                Thread.sleep(3500);
            } catch (InterruptedException ignored) {}
            while (true) {
                try {
                    if (chkCloudSync247 == null || chkCloudSync247.isSelected()) {
                        syncWithCloudServerSync(false);
                    }
                    Thread.sleep(15000);
                } catch (Throwable ignored) {
                    try {
                        Thread.sleep(15000);
                    } catch (InterruptedException e) {
                        break;
                    }
                }
            }
        }, "HSO-PC-Cloud247-Sync");
        t.setDaemon(true);
        t.start();
    }

    private static void syncWithCloudServerAsync(final boolean startCloudVm247) {
        new Thread(() -> syncWithCloudServerSync(startCloudVm247), "HSO-PC-CloudSync-Now").start();
    }

    private static void syncWithCloudServerSync(boolean startCloudVm247) {
        String rawBase = (txtCloudUrl != null && !txtCloudUrl.getText().trim().isEmpty())
                ? txtCloudUrl.getText().trim()
                : DEFAULT_CLOUD_URL;
        while (rawBase.endsWith("/")) {
            rawBase = rawBase.substring(0, rawBase.length() - 1);
        }
        final String baseUrl = rawBase;

        try {
            String gameUser = (txtQuickUser != null) ? txtQuickUser.getText().trim() : "";
            boolean aqActive = HeadlessJ2ME.isAutoQuestActive();
            String scope = HeadlessJ2ME.getCurrentQuestFilter();
            String summary = HeadlessJ2ME.getInGameAutoQuestSummary();
            String firstLine = summary != null ? summary.replace('\n', ' ').replace('"', '\'') : "";
            if (firstLine.length() > 120) {
                firstLine = firstLine.substring(0, 120);
            }

            // Đọc các file save RMS trên PC để đồng bộ lên Cloud
            StringBuilder rmsJson = new StringBuilder("{");
            File rmsDir = new File(currentWorkDir, "saves/rms_admin/rms");
            if (!rmsDir.exists()) {
                rmsDir = new File(currentWorkDir, "rms");
            }
            if (rmsDir.exists() && rmsDir.isDirectory()) {
                File[] files = rmsDir.listFiles();
                if (files != null) {
                    boolean first = true;
                    for (File f : files) {
                        if (f.isFile() && f.length() > 0 && f.length() <= 128 * 1024) {
                            byte[] data = readFileBytes(f);
                            if (data != null) {
                                if (!first) rmsJson.append(",");
                                first = false;
                                rmsJson.append("\"").append(escapeJson(f.getName())).append("\":\"")
                                       .append(Base64.getEncoder().encodeToString(data)).append("\"");
                            }
                        }
                    }
                }
            }
            rmsJson.append("}");

            String payload = "{"
                    + "\"pcClientId\":\"" + escapeJson(PC_CLIENT_ID) + "\","
                    + "\"username\":\"" + escapeJson(gameUser.isEmpty() ? PC_CLIENT_ID : gameUser) + "\","
                    + "\"gameUsername\":\"" + escapeJson(gameUser) + "\","
                    + "\"platform\":\"PC Desktop (Windows/Java)\","
                    + "\"autoQuest\":" + aqActive + ","
                    + "\"questScope\":\"" + escapeJson(scope) + "\","
                    + "\"startCloudVm247\":" + startCloudVm247 + ","
                    + "\"stateText\":\"" + escapeJson(firstLine) + "\","
                    + "\"rmsRecords\":" + rmsJson.toString()
                    + "}";

            URL url = new URL(baseUrl + "/api/pc/sync-session");
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("POST");
            conn.setConnectTimeout(8000);
            conn.setReadTimeout(10000);
            conn.setDoOutput(true);
            conn.setRequestProperty("Content-Type", "application/json; charset=UTF-8");

            byte[] bodyBytes = payload.getBytes(StandardCharsets.UTF_8);
            try (OutputStream os = conn.getOutputStream()) {
                os.write(bodyBytes);
            }

            int code = conn.getResponseCode();
            if (code >= 200 && code < 300) {
                if (lblCloudSyncStatus != null) {
                    SwingUtilities.invokeLater(() -> {
                        lblCloudSyncStatus.setForeground(new Color(52, 211, 153));
                        lblCloudSyncStatus.setText(startCloudVm247
                                ? "✅ Đã uỷ thác phiên & Save lên Cloud 24/7 (" + baseUrl + ")!"
                                : "🟢 Đang duy trì Mạng Kép 24/7 với " + baseUrl);
                    });
                }
                if (startCloudVm247) {
                    System.out.println("[CLOUD-24/7] Đã đẩy dữ liệu Save & kích hoạt Máy Ảo treo 24/7 trên " + baseUrl);
                }
            } else {
                // Fallback ping /api/health nếu server đang dùng bản cũ
                pingHealthFallback(baseUrl);
            }
            conn.disconnect();
        } catch (Throwable t) {
            pingHealthFallback(baseUrl);
        }
    }

    private static void pingHealthFallback(final String baseUrl) {
        try {
            URL url = new URL(baseUrl + "/api/health");
            HttpURLConnection conn = (HttpURLConnection) url.openConnection();
            conn.setRequestMethod("GET");
            conn.setConnectTimeout(6000);
            conn.setReadTimeout(6000);
            int code = conn.getResponseCode();
            conn.disconnect();
            if (code >= 200 && code < 300 && lblCloudSyncStatus != null) {
                SwingUtilities.invokeLater(() -> {
                    lblCloudSyncStatus.setForeground(new Color(56, 189, 248));
                    lblCloudSyncStatus.setText("🟢 Đã kết nối & giữ thức 24/7: " + baseUrl);
                });
            }
        } catch (Throwable err) {
            if (lblCloudSyncStatus != null) {
                SwingUtilities.invokeLater(() -> {
                    lblCloudSyncStatus.setForeground(new Color(251, 191, 36));
                    lblCloudSyncStatus.setText("📡 Đang chờ kết nối Mạng Kép tới " + baseUrl + "...");
                });
            }
        }
    }

    private static byte[] readFileBytes(File f) {
        try (FileInputStream in = new FileInputStream(f);
             ByteArrayOutputStream out = new ByteArrayOutputStream((int) f.length())) {
            byte[] buf = new byte[4096];
            int n;
            while ((n = in.read(buf)) > 0) {
                out.write(buf, 0, n);
            }
            return out.toByteArray();
        } catch (Throwable ignored) {
            return null;
        }
    }

    private static String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\r", "")
                .replace("\n", " ");
    }

    private static JPanel buildKeypadPanel(Color cardDark, Color borderCol) {
        JPanel pad = new JPanel(new BorderLayout(6, 6));
        pad.setBackground(cardDark);
        pad.setBorder(BorderFactory.createCompoundBorder(
                BorderFactory.createLineBorder(borderCol, 1),
                new EmptyBorder(8, 8, 8, 8)
        ));

        JPanel softRow = new JPanel(new GridLayout(1, 4, 5, 5));
        softRow.setBackground(cardDark);

        DarkButton btnLeftSoft = createKeyBtn("Trái (F1)", -6, new Color(51, 65, 85));
        DarkButton btnCenterOk = createKeyBtn("OK (Enter)", -5, new Color(37, 99, 235));
        DarkButton btnRightSoft = createKeyBtn("Phải (F2)", -7, new Color(51, 65, 85));

        DarkButton btnSwitchField = new DarkButton("Đổi Dòng TK/MK", new Color(124, 58, 237));
        btnSwitchField.setPreferredSize(new Dimension(80, 32));
        btnSwitchField.addActionListener(e -> {
            HeadlessJ2ME.dispatchCommand("SWITCH_LOGIN_FIELD");
            canvasPanel.requestFocusInWindow();
            canvasPanel.repaint();
        });

        softRow.add(btnLeftSoft);
        softRow.add(btnCenterOk);
        softRow.add(btnRightSoft);
        softRow.add(btnSwitchField);

        JPanel dpadGrid = new JPanel(new GridLayout(2, 3, 5, 5));
        dpadGrid.setBackground(cardDark);
        dpadGrid.add(createKeyBtn("Phím 1", 49, new Color(30, 58, 138)));
        dpadGrid.add(createKeyBtn("▲ Lên", -1, new Color(51, 65, 85)));
        dpadGrid.add(createKeyBtn("Phím 3", 51, new Color(30, 58, 138)));
        dpadGrid.add(createKeyBtn("◄ Trái", -3, new Color(51, 65, 85)));
        dpadGrid.add(createKeyBtn("▼ Xuống", -2, new Color(51, 65, 85)));
        dpadGrid.add(createKeyBtn("► Phải", -4, new Color(51, 65, 85)));

        pad.add(softRow, BorderLayout.NORTH);
        pad.add(dpadGrid, BorderLayout.CENTER);
        return pad;
    }

    private static DarkButton createKeyBtn(String label, int j2meKeyCode, Color bg) {
        DarkButton btn = new DarkButton(label, bg);
        btn.setPreferredSize(new Dimension(80, 32));
        btn.addMouseListener(new MouseAdapter() {
            @Override
            public void mousePressed(MouseEvent e) {
                HeadlessJ2ME.dispatchCommand("KD:" + j2meKeyCode);
                canvasPanel.repaint();
            }

            @Override
            public void mouseReleased(MouseEvent e) {
                HeadlessJ2ME.dispatchCommand("KU:" + j2meKeyCode);
                canvasPanel.requestFocusInWindow();
                canvasPanel.repaint();
            }
        });
        return btn;
    }

    private static JCheckBox createStyledCheck(String text, boolean selected, Color bg, Color fg) {
        JCheckBox chk = new JCheckBox(text, selected);
        chk.setBackground(bg);
        chk.setForeground(fg);
        chk.setOpaque(true);
        chk.setFocusPainted(false);
        chk.setFont(new Font("SansSerif", Font.BOLD, 12));
        return chk;
    }

    private static JPanel createStyledCard(String title, Color bg, Color borderCol, Color titleCol) {
        JPanel p = new JPanel();
        p.setBackground(bg);
        TitledBorder tb = BorderFactory.createTitledBorder(
                BorderFactory.createLineBorder(borderCol, 1),
                title,
                TitledBorder.LEFT,
                TitledBorder.TOP,
                new Font("SansSerif", Font.BOLD, 11),
                titleCol
        );
        p.setBorder(BorderFactory.createCompoundBorder(tb, new EmptyBorder(4, 6, 4, 6)));
        return p;
    }

    private static void sendAutoQuestFromUi(String mode) {
        int idx = Math.max(0, comboQuestType.getSelectedIndex());
        String scope = QUEST_SCOPE_CODES[Math.min(idx, QUEST_SCOPE_CODES.length - 1)];
        String pot = chkAutoPotion.isSelected() ? "1" : "0";
        String buyPot = chkAutoBuyPotion.isSelected() ? "1" : "0";
        HeadlessJ2ME.dispatchCommand("AUTO_QUEST:" + mode + ":" + scope + ":" + pot + ":1:" + buyPot);
        refreshUiFromEngine();
    }

    private static void refreshUiFromEngine() {
        try {
            syncingUi = true;
            boolean active = HeadlessJ2ME.isAutoQuestActive();
            if (active) {
                btnToggleAuto.setText("■ ĐANG CHẠY AUTO NV (BẤM ĐỂ TẮT)");
                btnToggleAuto.setButtonColor(new Color(220, 38, 38));
            } else {
                btnToggleAuto.setText("▶ BẬT AUTO LÀM NHIỆM VỤ");
                btnToggleAuto.setButtonColor(new Color(22, 163, 74));
            }

            String curFilter = HeadlessJ2ME.getCurrentQuestFilter();
            for (int i = 0; i < QUEST_SCOPE_CODES.length; i++) {
                if (QUEST_SCOPE_CODES[i].equalsIgnoreCase(curFilter)) {
                    if (comboQuestType.getSelectedIndex() != i) {
                        comboQuestType.setSelectedIndex(i);
                    }
                    break;
                }
            }

            chkAutoPotion.setSelected(HeadlessJ2ME.isAutoPotionActive());
            chkAutoBuyPotion.setSelected(HeadlessJ2ME.isAutoBuyPotionActive());
            chkAutoSell.setSelected(HeadlessJ2ME.isAutoSellActive());
            txtStatusSummary.setText(HeadlessJ2ME.getInGameAutoQuestSummary());
        } catch (Throwable ignored) {
        } finally {
            syncingUi = false;
        }
    }

    private static int mapPcKeyToJ2me(KeyEvent e, boolean tfieldActive) {
        int code = e.getKeyCode();
        switch (code) {
            case KeyEvent.VK_UP:
                return -1;
            case KeyEvent.VK_DOWN:
                return -2;
            case KeyEvent.VK_LEFT:
                return -3;
            case KeyEvent.VK_RIGHT:
                return -4;
            case KeyEvent.VK_ENTER:
                return -5;
            case KeyEvent.VK_F1:
                return -6;
            case KeyEvent.VK_F2:
                return -7;
            case KeyEvent.VK_BACK_SPACE:
            case KeyEvent.VK_DELETE:
                return -8;
            default:
                if (tfieldActive) {
                    return Integer.MIN_VALUE;
                }
                if (code == KeyEvent.VK_W) return -1;
                if (code == KeyEvent.VK_S) return -2;
                if (code == KeyEvent.VK_A) return -3;
                if (code == KeyEvent.VK_D) return -4;
                if (code == KeyEvent.VK_SPACE) return -5;
                if (code == KeyEvent.VK_Q) return -6;
                if (code == KeyEvent.VK_E) return -7;
                if (code >= KeyEvent.VK_0 && code <= KeyEvent.VK_9) {
                    return 48 + (code - KeyEvent.VK_0);
                }
                if (code >= KeyEvent.VK_NUMPAD0 && code <= KeyEvent.VK_NUMPAD9) {
                    return 48 + (code - KeyEvent.VK_NUMPAD0);
                }
                return Integer.MIN_VALUE;
        }
    }

    private static boolean shouldSuppressNoiseLine(String line) {
        if (line == null) return true;
        String trimmed = line.trim();
        if (trimmed.isEmpty()) return true;
        return trimmed.startsWith("Error Adapting Class org.microemu")
                || trimmed.startsWith("Couldn't Load Image Stream")
                || trimmed.startsWith("FRAME:");
    }

    private static void hookStreamsToGuiConsole() {
        final PrintStream origErr = System.err;
        OutputStream tee = new OutputStream() {
            private final ByteArrayOutputStream lineBuf = new ByteArrayOutputStream(256);

            @Override
            public synchronized void write(int b) {
                if (b == '\n') {
                    flushLine();
                } else if (b != '\r') {
                    lineBuf.write(b);
                }
            }

            @Override
            public synchronized void write(byte[] buf, int off, int len) {
                for (int i = off; i < off + len; i++) {
                    byte b = buf[i];
                    if (b == '\n') {
                        flushLine();
                    } else if (b != '\r') {
                        lineBuf.write(b);
                    }
                }
            }

            private void flushLine() {
                if (lineBuf.size() == 0) return;
                final String line = new String(lineBuf.toByteArray(), StandardCharsets.UTF_8);
                lineBuf.reset();
                if (shouldSuppressNoiseLine(line)) {
                    return;
                }
                origErr.println(line);
                if (txtLogConsole != null) {
                    SwingUtilities.invokeLater(() -> {
                        txtLogConsole.append(line + "\n");
                        int len = txtLogConsole.getDocument().getLength();
                        if (len > 24000) {
                            txtLogConsole.replaceRange("", 0, len - 16000);
                        }
                        txtLogConsole.setCaretPosition(txtLogConsole.getDocument().getLength());
                    });
                }
            }
        };
        try {
            PrintStream ps = new PrintStream(tee, true, "UTF-8");
            System.setErr(ps);
            System.setOut(ps);
        } catch (Throwable ignored) {}
    }
}
