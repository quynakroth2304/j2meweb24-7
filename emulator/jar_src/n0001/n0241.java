package n0001;

import java.lang.reflect.Method;
import javax.microedition.rms.RecordStore;

public final class n0241 extends n0094 {
    private static n0241 instance;
    private static boolean rmsLoaded = false;

    public static volatile boolean aqEnabled = false;
    public static volatile String aqScope = "all"; // all | main | sub | script | farm
    public static volatile boolean aqPotion = true;
    public static volatile boolean aqBuyPotion = true;
    public static volatile boolean aqSell = true;

    private static final String RMS_STORE_NAME = "hso_aq_menu_cfg";

    private n0241() {
        super();
        instance = this;
    }

    public n0241(byte b) {
        this();
    }

    public static n0241 getInstance() {
        if (instance == null) {
            instance = new n0241((byte) 0);
        }
        return instance;
    }

    public static void injectAutoQuestMenuItems(n0184 list, String tag) {
        if (list == null || tag == null) {
            return;
        }
        boolean isVipMenu = tag.startsWith("VIP ");
        boolean isGameFuncMenu = "GameScr menu3".equals(tag);
        boolean isGameMainMenu = "GameScr menu".equals(tag) && n0041.n0048 != null;

        if (!isVipMenu && !isGameFuncMenu && !isGameMainMenu) {
            return;
        }

        ensureStateSynced();
        n0241 listener = getInstance();
        String stateBadge = aqEnabled ? "BẬT" : "TẮT";
        String scopeShort = getScopeShortBadge(aqScope);

        list.n0001(new n0174("Auto Nhiệm Vụ: " + stateBadge + " [" + scopeShort + "]", -10, listener));
        list.n0001(new n0174("Chọn Loại NV: " + getScopeDisplayName(aqScope), -11, listener));
    }

    public final void n0003(int cmdId, int sub) {
        if (cmdId <= -10 && cmdId >= -30) {
            handleAutoQuestCommand(cmdId);
            return;
        }
        n0235.n0001(cmdId, null);
    }

    public final void n0001(int cmdId, Object obj) {
        if (cmdId <= -10 && cmdId >= -30) {
            handleAutoQuestCommand(cmdId);
            return;
        }
        n0235.n0001(cmdId, obj);
    }

    public static void openAutoQuestMainMenu() {
        ensureStateSynced();
        n0241 listener = getInstance();
        n0184 menu = new n0184();
        menu.n0001(new n0174("Tự làm nhiệm vụ: " + (aqEnabled ? "BẬT" : "TẮT"), -12, listener));
        menu.n0001(new n0174("Chọn loại NV: " + getScopeDisplayName(aqScope), -11, listener));
        menu.n0001(new n0174("Thực hiện ngay 1 bước NV", -13, listener));
        menu.n0001(new n0174("Tự dùng bình HP/MP: " + (aqPotion ? "BẬT" : "TẮT"), -14, listener));
        menu.n0001(new n0174("Tự mua HP/MP khi hết: " + (aqBuyPotion ? "BẬT" : "TẮT"), -15, listener));
        menu.n0001(new n0174("Tự bán đồ đầy túi: " + (aqSell ? "BẬT" : "TẮT"), -16, listener));
        menu.n0001(new n0174("Trạng thái & Tiến độ NV", -17, listener));
        menu.n0001(new n0174("Quay lại Menu VIP ADMIN", -18, listener));
        n0235.n0001(menu, "AUTO LÀM NHIỆM VỤ");
    }

    public static void openQuestTypeSelectorMenu() {
        ensureStateSynced();
        n0241 listener = getInstance();
        n0184 menu = new n0184();
        menu.n0001(new n0174((isScope("all") ? "✓ " : "") + "1. Tất cả NV (Chính + Phụ)", -20, listener));
        menu.n0001(new n0174((isScope("main") ? "✓ " : "") + "2. Chỉ NV Chính Tuyến", -21, listener));
        menu.n0001(new n0174((isScope("sub") ? "✓ " : "") + "3. Chỉ NV Phụ / Hằng Ngày", -22, listener));
        menu.n0001(new n0174((isScope("script") ? "✓ " : "") + "4. Kịch Bản Chuẩn Lv.1 -> 135", -23, listener));
        menu.n0001(new n0174((isScope("farm") ? "✓ " : "") + "5. Chỉ Cày Quái Theo Level", -24, listener));
        menu.n0001(new n0174("▶ Bật Auto NV với loại này", -25, listener));
        menu.n0001(new n0174("Quay lại Auto Nhiệm Vụ", -10, listener));
        n0235.n0001(menu, "CHỌN LOẠI NHIỆM VỤ");
    }

    private static void handleAutoQuestCommand(int cmdId) {
        try {
            ensureStateSynced();
            switch (cmdId) {
                case -10:
                    openAutoQuestMainMenu();
                    return;
                case -11:
                    openQuestTypeSelectorMenu();
                    return;
                case -12:
                    aqEnabled = !aqEnabled;
                    saveStateToRms();
                    applyAutoQuestToEngine(false);
                    n0235.n0001("Auto Nhiệm Vụ", (aqEnabled ? "Đã BẬT (" : "Đã TẮT (") + getScopeDisplayName(aqScope) + ")");
                    openAutoQuestMainMenu();
                    return;
                case -13:
                    applyAutoQuestToEngine(true);
                    n0235.n0001("Auto Nhiệm Vụ", "Đang chạy 1 bước (" + getScopeDisplayName(aqScope) + ")");
                    return;
                case -14:
                    aqPotion = !aqPotion;
                    saveStateToRms();
                    applyAutoQuestToEngine(false);
                    n0235.n0001("Tự dùng HP/MP", aqPotion ? "Đã BẬT" : "Đã TẮT");
                    openAutoQuestMainMenu();
                    return;
                case -15:
                    aqBuyPotion = !aqBuyPotion;
                    saveStateToRms();
                    applyAutoQuestToEngine(false);
                    n0235.n0001("Tự mua HP/MP", aqBuyPotion ? "Đã BẬT" : "Đã TẮT");
                    openAutoQuestMainMenu();
                    return;
                case -16:
                    aqSell = !aqSell;
                    saveStateToRms();
                    dispatchHeadlessCommand("AUTO_SELL:" + (aqSell ? "1" : "0") + ":1:1:1:0:0:1:1:0:40");
                    if (n0235.n0001 != null) {
                        n0235.n0001.n0021 = aqSell;
                    }
                    n0235.n0001("Tự bán đồ", aqSell ? "Đã BẬT (Trắng/Xanh/Vàng)" : "Đã TẮT");
                    openAutoQuestMainMenu();
                    return;
                case -17:
                    n0235.n0003(buildStatusDialogText());
                    return;
                case -18:
                    n0235.n0009();
                    return;
                case -20:
                    selectQuestScope("all");
                    return;
                case -21:
                    selectQuestScope("main");
                    return;
                case -22:
                    selectQuestScope("sub");
                    return;
                case -23:
                    selectQuestScope("script");
                    return;
                case -24:
                    selectQuestScope("farm");
                    return;
                case -25:
                    aqEnabled = true;
                    saveStateToRms();
                    applyAutoQuestToEngine(false);
                    n0235.n0001("Auto Nhiệm Vụ", "Đã BẬT: " + getScopeDisplayName(aqScope));
                    openAutoQuestMainMenu();
                    return;
                default:
                    openAutoQuestMainMenu();
            }
        } catch (Throwable t) {
            n0235.n0001("Auto Nhiệm Vụ", "Lỗi: " + t.getClass().getSimpleName());
        }
    }

    private static void selectQuestScope(String newScope) {
        aqScope = newScope;
        saveStateToRms();
        applyAutoQuestToEngine(false);
        n0235.n0001("Loại Nhiệm Vụ", "Đã chọn: " + getScopeDisplayName(aqScope));
        openQuestTypeSelectorMenu();
    }

    private static boolean isScope(String target) {
        if (aqScope == null) {
            return "all".equals(target);
        }
        return aqScope.equalsIgnoreCase(target);
    }

    public static String getScopeShortBadge(String scope) {
        if ("main".equalsIgnoreCase(scope)) {
            return "NV Chính";
        }
        if ("sub".equalsIgnoreCase(scope)) {
            return "NV Phụ";
        }
        if ("script".equalsIgnoreCase(scope)) {
            return "Kịch Bản 1-135";
        }
        if ("farm".equalsIgnoreCase(scope)) {
            return "Cày Quái Lv";
        }
        return "Tất Cả NV";
    }

    public static String getScopeDisplayName(String scope) {
        if ("main".equalsIgnoreCase(scope)) {
            return "Chỉ NV Chính Tuyến";
        }
        if ("sub".equalsIgnoreCase(scope)) {
            return "Chỉ NV Phụ / Hằng Ngày";
        }
        if ("script".equalsIgnoreCase(scope)) {
            return "Kịch Bản Chuẩn Lv.1 -> 135";
        }
        if ("farm".equalsIgnoreCase(scope)) {
            return "Chỉ Cày Quái Theo Level";
        }
        return "Tất cả NV (Chính + Phụ)";
    }

    private static void applyAutoQuestToEngine(boolean singleStep) {
        String mode = singleStep ? "step" : (aqEnabled ? "1" : "0");
        String cmd = "AUTO_QUEST:" + mode + ":" + aqScope + ":" + (aqPotion ? "1" : "0") + ":1:" + (aqBuyPotion ? "1" : "0");
        boolean sentToHeadless = dispatchHeadlessCommand(cmd);
        if ("script".equalsIgnoreCase(aqScope) && aqEnabled) {
            dispatchHeadlessCommand("AUTO_QUEST:script:1");
        }

        // Đồng bộ trực tiếp với các module tự đánh / tự dùng thuốc / tự bán đồ trong JAR (n0235)
        try {
            if (n0235.n0001 != null) {
                n0235.n0001.n0015 = aqPotion;
                n0235.n0001.n0021 = aqSell;
                if ("farm".equalsIgnoreCase(aqScope) || !sentToHeadless) {
                    n0235.n0001.n0039 = aqEnabled;
                }
            }
            if (!sentToHeadless && n0235.n0002 != null) {
                n0235.n0002.n0001(aqEnabled);
            }
        } catch (Throwable ignored) {
        }
    }

    private static String buildStatusDialogText() {
        Class<?> cls = findHeadlessClass();
        if (cls != null) {
            try {
                Method m = cls.getMethod("getInGameAutoQuestSummary");
                Object res = m.invoke(null);
                if (res instanceof String) {
                    return (String) res;
                }
            } catch (Throwable ignored) {
            }
        }
        int lv = -1;
        int mapId = -1;
        try {
            lv = n0213.n0011_level();
            mapId = n0213.n0004();
        } catch (Throwable ignored) {
        }
        return "AUTO LÀM NHIỆM VỤ - DUY TRƯỜNG\n"
                + "• Trạng thái: " + (aqEnabled ? "ĐANG BẬT" : "ĐANG TẮT") + "\n"
                + "• Loại nhiệm vụ: " + getScopeDisplayName(aqScope) + "\n"
                + "• Cấp nhân vật: " + (lv > 0 ? String.valueOf(lv) : "Chưa vào game")
                + " | Map ID: " + (mapId >= 0 ? String.valueOf(mapId) : "-") + "\n"
                + "• Tự dùng bình HP/MP: " + (aqPotion ? "BẬT" : "TẮT") + "\n"
                + "• Tự mua HP/MP khi hết: " + (aqBuyPotion ? "BẬT" : "TẮT") + "\n"
                + "• Tự bán đồ đầy túi: " + (aqSell ? "BẬT" : "TẮT");
    }

    private static void ensureStateSynced() {
        if (!rmsLoaded) {
            rmsLoaded = true;
            loadStateFromRms();
        }
        Class<?> cls = findHeadlessClass();
        if (cls != null) {
            try {
                Method mOn = cls.getMethod("isAutoQuestActive");
                Object rOn = mOn.invoke(null);
                if (rOn instanceof Boolean) {
                    aqEnabled = ((Boolean) rOn).booleanValue();
                }
                Method mFilter = cls.getMethod("getCurrentQuestFilter");
                Object rFilter = mFilter.invoke(null);
                if (rFilter instanceof String && ((String) rFilter).length() > 0) {
                    aqScope = (String) rFilter;
                }
                Method mPot = cls.getMethod("isAutoPotionActive");
                Object rPot = mPot.invoke(null);
                if (rPot instanceof Boolean) {
                    aqPotion = ((Boolean) rPot).booleanValue();
                }
                Method mBuy = cls.getMethod("isAutoBuyPotionActive");
                Object rBuy = mBuy.invoke(null);
                if (rBuy instanceof Boolean) {
                    aqBuyPotion = ((Boolean) rBuy).booleanValue();
                }
                Method mSell = cls.getMethod("isAutoSellActive");
                Object rSell = mSell.invoke(null);
                if (rSell instanceof Boolean) {
                    aqSell = ((Boolean) rSell).booleanValue();
                }
            } catch (Throwable ignored) {
            }
        }
    }

    private static boolean dispatchHeadlessCommand(String cmd) {
        Class<?> cls = findHeadlessClass();
        if (cls == null) {
            return false;
        }
        try {
            Method m = cls.getMethod("dispatchCommand", String.class);
            m.invoke(null, cmd);
            return true;
        } catch (Throwable ignored) {
            return false;
        }
    }

    private static Class<?> findHeadlessClass() {
        String[] candidates = new String[] {
            "com.example.runtime.HeadlessJ2ME",
            "HeadlessJ2ME"
        };
        for (int i = 0; i < candidates.length; i++) {
            try {
                return Class.forName(candidates[i]);
            } catch (Throwable ignored) {
            }
        }
        return null;
    }

    private static void loadStateFromRms() {
        RecordStore rs = null;
        try {
            rs = RecordStore.openRecordStore(RMS_STORE_NAME, false);
            if (rs.getNumRecords() >= 1) {
                byte[] data = rs.getRecord(1);
                if (data != null && data.length > 0) {
                    String s = new String(data, "UTF-8");
                    String[] parts = splitSimple(s, '|');
                    if (parts.length >= 1 && parts[0].length() > 0) {
                        aqScope = parts[0];
                    }
                    if (parts.length >= 2) {
                        aqPotion = "1".equals(parts[1]);
                    }
                    if (parts.length >= 3) {
                        aqBuyPotion = "1".equals(parts[2]);
                    }
                    if (parts.length >= 4) {
                        aqSell = "1".equals(parts[3]);
                    }
                }
            }
        } catch (Throwable ignored) {
        } finally {
            if (rs != null) {
                try {
                    rs.closeRecordStore();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    private static void saveStateToRms() {
        RecordStore rs = null;
        try {
            rs = RecordStore.openRecordStore(RMS_STORE_NAME, true);
            String payload = aqScope + "|" + (aqPotion ? "1" : "0") + "|" + (aqBuyPotion ? "1" : "0") + "|" + (aqSell ? "1" : "0");
            byte[] data = payload.getBytes("UTF-8");
            if (rs.getNumRecords() == 0) {
                rs.addRecord(data, 0, data.length);
            } else {
                rs.setRecord(1, data, 0, data.length);
            }
        } catch (Throwable ignored) {
        } finally {
            if (rs != null) {
                try {
                    rs.closeRecordStore();
                } catch (Throwable ignored) {
                }
            }
        }
    }

    private static String[] splitSimple(String str, char sep) {
        int count = 1;
        for (int i = 0; i < str.length(); i++) {
            if (str.charAt(i) == sep) {
                count++;
            }
        }
        String[] out = new String[count];
        int start = 0;
        int idx = 0;
        for (int i = 0; i < str.length(); i++) {
            if (str.charAt(i) == sep) {
                out[idx++] = str.substring(start, i);
                start = i + 1;
            }
        }
        out[idx] = str.substring(start);
        return out;
    }
}
