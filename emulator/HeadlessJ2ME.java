// File: j2meweb/emulator/HeadlessJ2ME.java
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.awt.image.DataBufferInt;
import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.reflect.Array;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.Collections;
import java.util.Enumeration;
import java.util.HashSet;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.imageio.ImageIO;
import org.recompile.mobile.Mobile;
import org.recompile.mobile.MobilePlatform;

public class HeadlessJ2ME {
    static {
        // Luon giu co che JNI Native Accessor (noInflation = false) va dat nguong inflationThreshold = MAX_INT
        // de JVM khong bao gio sinh class jdk/internal/reflect/* ben trong MIDletLoader cua FreeJ2ME
        try {
            System.setProperty("sun.reflect.noInflation", "false");
            System.setProperty("sun.reflect.inflationThreshold", "2147483647");
        } catch (Throwable ignored) {}
        for (String rfName : new String[]{"jdk.internal.reflect.ReflectionFactory", "sun.reflect.ReflectionFactory"}) {
            try {
                Class<?> rfCls = Class.forName(rfName);
                Field fNoInf = rfCls.getDeclaredField("noInflation");
                fNoInf.setAccessible(true);
                fNoInf.setBoolean(null, false);
                Field fThresh = rfCls.getDeclaredField("inflationThreshold");
                fThresh.setAccessible(true);
                fThresh.setInt(null, Integer.MAX_VALUE);
            } catch (Throwable ignored) {}
        }
    }
    public static void shutdownTenant() {
    try {
        javax.microedition.io.Connector.closeAllTenantSockets();
    } catch (Throwable ignored) {}
}
    private static final Map<Method, MethodHandle> METHOD_HANDLE_CACHE = new ConcurrentHashMap<>();
    private static final Map<Constructor<?>, MethodHandle> CTOR_HANDLE_CACHE = new ConcurrentHashMap<>();
    private static final Set<Class<?>> NON_VECTOR_CLASSES = Collections.newSetFromMap(new ConcurrentHashMap<>());

    private static Object invokeMethod(Method m, Object target, Object... args) throws Throwable {
        if (m == null) return null;
        try {
            return m.invoke(target, args);
        } catch (java.lang.reflect.InvocationTargetException ite) {
            throw (ite.getTargetException() != null) ? ite.getTargetException() : ite;
        } catch (LinkageError le) {
            MethodHandle mh = METHOD_HANDLE_CACHE.computeIfAbsent(m, method -> {
                try {
                    method.setAccessible(true);
                    return MethodHandles.lookup().unreflect(method).asFixedArity();
                } catch (Throwable t) {
                    return null;
                }
            });
            if (mh != null) {
                int argLen = (args == null) ? 0 : args.length;
                if (Modifier.isStatic(m.getModifiers())) {
                    return (argLen == 0) ? mh.invokeWithArguments() : mh.invokeWithArguments(args);
                }
                Object[] fullArgs = new Object[argLen + 1];
                fullArgs[0] = target;
                if (argLen > 0) System.arraycopy(args, 0, fullArgs, 1, argLen);
                return mh.invokeWithArguments(fullArgs);
            }
            throw le;
        }
    }

    private static Object invokeCtor(Constructor<?> ctor, Object... args) throws Throwable {
        if (ctor == null) return null;
        try {
            return ctor.newInstance(args);
        } catch (java.lang.reflect.InvocationTargetException ite) {
            throw (ite.getTargetException() != null) ? ite.getTargetException() : ite;
        } catch (LinkageError le) {
            MethodHandle mh = CTOR_HANDLE_CACHE.computeIfAbsent(ctor, c -> {
                try {
                    c.setAccessible(true);
                    return MethodHandles.lookup().unreflectConstructor(c).asFixedArity();
                } catch (Throwable t) {
                    return null;
                }
            });
            if (mh != null) {
                return (args == null || args.length == 0) ? mh.invokeWithArguments() : mh.invokeWithArguments(args);
            }
            throw le;
        }
    }

    private static volatile boolean dirty = true;
    private static volatile boolean running = true;
    private static MobilePlatform platform;
    private static PrintStream ipcOut;
    private static String loadedJarPath;
    private static List<String> cachedGameClassNames = null;
    private static int[] lastPixels = null;
    private static int lastWidth = 0;
    private static int lastHeight = 0;

    // --- BO BAO VE DA MUC TIEU AUTO-QUEST (Sửa B8) ---
    private static final java.util.Set<String> exhaustedQuestTargets = java.util.Collections.synchronizedSet(new java.util.HashSet<>());
    private static int lastTrackedQuestId = -1;
    private static int lastTrackedStepMobId = -1;
    private static int lastTrackedProgress = -1;
    private static int lastAttackedMobTemplateId = -1;
    private static int lastAttackedMapId = -1;
    private static int sameMobAttackCountWithoutProgress = 0;
    private static int killsWithoutProgressCount = 0;
    private static int lastAttackedMobHp = -1;
    private static long lastMobDamageDealtTime = 0L;
    private static long firstAttackTimeWithoutProgress = 0L;
    private static long lastProgressIncreaseTime = System.currentTimeMillis();

    // --- DIEU KHIEN AUTO NHIEM VU, THUOC & IPC STATUS ---
    private static volatile boolean autoQuestEnabled = false;
    private static volatile boolean autoPotionEnabled = true;
    private static volatile boolean autoBuyPotionEnabled = true;
    private static volatile boolean aqStatusIpcEnabled = false;
    private static volatile boolean stepRequested = false;
    private static volatile String autoQuestFilter = "all";

    // --- K\u1ecaCH B\u1ea2N NHI\u1ec6M V\u1ee4 (SCRIPT) + RECORDER ---
    // K\u1ecbch b\u1ea3n = danh s\u00e1ch b\u01b0\u1edbc c\u1ed1 \u0111\u1ecbnh theo th\u1ee9 t\u1ef1. M\u1ed7i tick ch\u1ec9 x\u00e9t \u0111\u00fang b\u01b0\u1edbc scriptIdx (con tr\u1ecf).
    private static final class QStep {
        int questId = -1;
        String name = "";
        boolean main = true;
        int type = -1;
        int minLevel = -1;            // c\u00e0y \u0111\u1ee7 Lv n\u00e0y r\u1ed3i m\u1edbi nh\u1eadn/l\u00e0m (-1 = kh\u00f4ng \u00e9p)
        int giveNpc = -1, giveMap = -1;   // n\u01a1i nh\u1eadn
        int turnNpc = -1, turnMap = -1;   // n\u01a1i tr\u1ea3 / n\u00f3i chuy\u1ec7n
        int mobId = -1, mobMap = -1;      // qu\u00e1i c\u1ea7n \u0111\u00e1nh + map (-1 = \u0111\u1ec3 bot t\u1ef1 suy ra)
        int count = -1;
        int spotX = -1, spotY = -1;       // t\u00e2m b\u00e3i c\u1ed1 \u0111\u1ecbnh (-1 = \u0111\u1ec3 bot t\u1ef1 ch\u1ecdn)
        // --- ch\u1ec9 d\u00f9ng cho recorder ---
        boolean seenNew, seenDoing, seenFinish, progressRecorded;
        int seq = -1;
        int lastCurSum = -1;
    }

    private static volatile boolean questScriptMode = false;
    private static volatile boolean questRecordEnabled = false;
    private static volatile boolean questScriptLoadedOnce = false;
    private static volatile List<QStep> questScript = new ArrayList<>();
    private static final Map<Integer, QStep> questScriptByQuestId = new ConcurrentHashMap<>();
    private static volatile int scriptIdx = 0;
    private static int scriptStepQuestId = Integer.MIN_VALUE;
    private static boolean scriptStepSeen = false;
    private static long scriptAbsentSinceMs = 0L;
    private static long scriptWaitUnlockSinceMs = 0L;
    // Ghi \u0111\u00e8 d\u00e0nh cho executeActiveQuestStep / resolveNpcReportMapId khi \u0111ang ch\u1ea1y k\u1ecbch b\u1ea3n
    private static volatile int scriptOverrideQuestId = -1;
    private static volatile int scriptOverrideMobId = -1;
    private static volatile int scriptOverrideMapId = -1;
    private static volatile int scriptOverrideNpcMap = -1;
    private static volatile int scriptOverrideMinLv = -1;
    private static volatile int scriptOverrideSpotX = -1;
    private static volatile int scriptOverrideSpotY = -1;
    private static final long SCRIPT_GONE_CONFIRM_MS = 2500L;
    private static final long SCRIPT_LOOKAHEAD_AFTER_MS = 6000L;
    private static final long SCRIPT_UNLOCK_WAIT_ADVANCE_MS = 45000L;
    // Recorder
    private static final Map<Integer, QStep> recordedQuests = new java.util.LinkedHashMap<>();
    private static int recordSeq = 0;
    private static long lastRecordTickMs = 0L;
    private static long lastRecordWriteMs = 0L;
    private static String lastRecordJson = "";

    // --- CAU HINH TU DONG CONG DIEM TIEM NANG KHI LEN LEVEL ---
    private static volatile boolean autoPotentialEnabled = false;
    private static final int[] autoPotentialPerLevel = new int[]{0, 0, 0, 0};
    private static final String[] STAT_NAMES = new String[]{"S\u1ee9c m\u1ea1nh", "Kh\u00e9o l\u00e9o", "Th\u1ec3 l\u1ef1c", "Tinh th\u1ea7n"};

    // --- CAU HINH TU DONG DANH QUAI THEO LEVEL ---
    private static final int[] LEVEL_DIFF_PRIORITY_ORDER = new int[]{-2, -1, 0, 1, 2};
    private static final Map<Integer, Integer> mapObservedMaxMobLv = new ConcurrentHashMap<>();
    private static final Map<Integer, Integer> mapObservedMinMobLv = new ConcurrentHashMap<>();
    private static int currentSelectedFarmMobLv = -1;
    private static volatile boolean modNativeLevelFarmActive = false;

    // --- CAU HINH TU DONG BAN VAT PHAM KHI DAY HANH TRANG ---
    private static volatile boolean autoSellEnabled = true;
    private static final boolean[] autoSellColorFlags = new boolean[]{true, true, true, false, false};
    private static final String[] ITEM_COLOR_NAMES = new String[]{"Tr\u1eafng", "Xanh", "V\u00e0ng", "T\u00edm", "Cam"};
    private static volatile boolean autoSellLocked = true;
    private static volatile boolean autoSellUnlocked = true;
    private static volatile boolean autoSellEnhanced = false;
    private static volatile boolean autoSellUnenhanced = true;
    private static volatile int autoSellBagThreshold = 40;
    private static volatile boolean autoSellNowRequested = false;
    private static volatile boolean inventoryFullDialogDetected = false;
    private static volatile boolean isAutoSellingSession = false;
    private static long lastAutoSellCheckTime = 0;
    private static long lastAutoSellActionTime = 0;
    private static int totalAutoSoldItemsCount = 0;
    private static int lastObservedBagCount = 0;
    public static volatile boolean renderEnabled = true;
    private static int lastObservedMaxBag = 42;
    private static Field cachedVecInventoryField = null;
    private static Field cachedMaxInventoryField = null;
    private static Thread autoQuestThread = null;
    private static final Object TICK_LOCK = new Object();

    private static final int MAX_ALLOWED_MOB_LEVEL_GAP = 1;

    // --- TRANG THAI VI TRI & CHONG KET (WATCHDOG - Sửa B7) ---
    private static int lastPx = -1;
    private static int lastPy = -1;
    private static long lastPositionChangeTime = System.currentTimeMillis();
    private static long lastRouteTime = 0;
    private static long lastNavRejectBackoffUntil = 0;
    private static int currentControlPriority = 1;
    private static Field cachedLockMgrTokenField = null;
    private static Field cachedTokenOwnerField = null;
    private static long modAutoSellYieldStartTime = 0L;
    private static long lastAutoSellYieldCooldownUntil = 0L;
    private static int bagCountAtYieldStart = -1;
    private static int lastSeenBagCountForYield = -1;
    private static int consecutiveStuckRecoveries = 0;
    private static long lastWatchdogActionTime = 0;
    private static int activeDirectionalKey = 0;

    // --- TRANG THAI CHAN DOAN & LOG RATE-LIMIT ---
    private static final Map<String, Long> lastLogByCategory = new ConcurrentHashMap<>();
    private static final Set<String> loggedReflectionErrors = Collections.newSetFromMap(new ConcurrentHashMap<>());
    private static long lastAqStatusSendTime = 0;
    private static String currentAqStateLabel = "IDLE";
    private static String currentAqDetailLabel = "Ch\u1edd k\u00edch ho\u1ea1t";
    private static String lastNavResultMsg = "NONE";
    // --- CAU HINH & TRANG THAI TU DONG DO KHU / DOI KHU NE TRANH BAI ---
    private static volatile boolean autoZoneEnabled = true;
    private static volatile int autoZoneMaxPlayersInSpot = 1; // Doi khu neu co >= 1 nguoi choi khac dung tranh bai
    private static volatile int manualTargetZoneRequest = -1;
    private static volatile boolean autoZoneDiagRequested = false;
    private static int currentZoneId = 0;
    private static int lastObservedPlayersInSpot = 0;
    private static int lastObservedPlayersOnMap = 0;
    private static long spotCrowdedSinceTime = 0L;
    private static long lastZoneSwitchTime = 0L;
    private static long lastZoneMenuOpenTime = 0L;
    private static boolean waitingForZoneListResponse = false;
    private static final long ZONE_SWITCH_COOLDOWN_MS = 15000L; // Cooldown 15s giua 2 lan doi khu
    private static final long CROWDED_CONFIRM_MS = 4000L;       // Xac nhan bi tranh bai lien tuc trong 4s moi doi
    private static final Map<Integer, Long> visitedCrowdedZones = new ConcurrentHashMap<>();
    private static Field cachedZoneArrayField = null;
    private static Field cachedCurrentZoneField = null;
    private static Method cachedChangeZoneMethod = null;
    // --- TRANG THAI DIEU HUONG & CHIEN DAU ---
    private static Object autoQuestRouteToken = null;
    private static int currentRoutingTargetMap = -1;
    private static int currentRoutingTargetX = -1;
    private static int currentRoutingTargetY = -1;
    private static int lastRoutedFromMapId = -1;
    private static boolean currentRoutingTeleportMode = false;
    private static int lastRecordedMapId = -1;
    private static int lastPreviousMapId = -1;
    private static long lastAttackKeyTime = 0;
    private static long lastPickupKeyTime = 0;
    private static long currentMapEnterTime = System.currentTimeMillis();
    private static long lastTargetSeenOrQuestChangeTime = System.currentTimeMillis();
    private static int lastPatrolIndex = 0;
    private static int lastLoggedQuestId = -1;
    private static int lastLoggedMapMobs = -1;
    private static int lastLoggedMapNpcs = -1;
    private static boolean mapNamesLoggedOnce = false;

    private static Object lockedTargetMob = null;
    private static Object approachTrackedMob = null;
    private static long approachStartTime = 0;
    private static int approachInitialDist = Integer.MAX_VALUE;
    private static int wallSlideToggle = 0;
    private static final Map<Object, Long> temporarilyIgnoredMobs =
            Collections.synchronizedMap(new IdentityHashMap<>());
    private static final String[] FULL_SKILL_PRIORITY = new String[]{"5", "4", "3", "2", "1"};
    private static final int[] SKILL_KEY_ROTATION = new int[]{53, 52, 51, 50, 49};
    private static int currentSkillRotationIdx = 0;

    // --- BO NHO THE GIOI (Sửa B3 & B12: Thêm observedQuestMobLevel & TTL cho Map Lock) ---
    private static final Map<Integer, Integer> knownNpcMap = new ConcurrentHashMap<>();
    private static final Map<Integer, Integer> knownMobMap = new ConcurrentHashMap<>();
    private static final Map<Integer, Integer> knownMobLevelMap = new ConcurrentHashMap<>();
    private static final Map<Integer, Integer> observedQuestMobLevel = new ConcurrentHashMap<>();
    private static final Map<Integer, String> knownMobNameMap = new ConcurrentHashMap<>();
    private static final Map<Integer, Integer> questOriginMap = new ConcurrentHashMap<>();
    private static final Set<Integer> searchedEmptyMaps = Collections.newSetFromMap(new ConcurrentHashMap<>());
    private static final Map<Integer, Long> searchedEmptyUntilMs = new ConcurrentHashMap<>();
    private static final Set<Integer> searchedMissingNpcMaps = Collections.newSetFromMap(new ConcurrentHashMap<>());
    private static final Set<Integer> ignoredNpcIds = Collections.newSetFromMap(new ConcurrentHashMap<>());
    private static final Set<Integer> mapsWithTeleportStone = Collections.newSetFromMap(new ConcurrentHashMap<>());
    private static long lastKnowledgeSaveTime = 0;
    private static volatile boolean knowledgeDirty = false;
    private static final long MAP_LOCK_TTL_MS = 5L * 60L * 1000L; // 5 phut TTL

    static {
        mapsWithTeleportStone.addAll(Arrays.asList(
                1, 5, 9, 11, 12, 13, 16, 19, 23, 25, 27, 32, 33, 39, 41, 45, 50, 67, 69, 73, 79, 92, 96
        ));
        // --- PRE-SEED TOAN BO NPC THE GIOI TU LV.1 DEN LV.135 ---
        int[][] defaultNpcMaps = new int[][]{
                {-1, 0}, {-2, 1}, {-3, 1}, {-4, 1}, {-5, 1}, {-6, 1}, {-7, 1}, {-8, 1},
                {-9, 12}, {-10, 1}, {-12, 1}, {-19, 34}, {-20, 33}, {-21, 33}, {-22, 33},
                {-23, 33}, {-24, 33}, {-25, 33}, {-26, 33}, {-29, 1}, {-30, 1}, {-31, 1},
                {-32, 1}, {-33, 1}, {-34, 7}, {-35, 7}, {-36, 1}, {-37, 1}, {-42, 33},
                {-44, 67}, {-48, 67}, {-52, 67}, {-54, 19}, {-59, 17}, {-69, 1}, {-71, 67},
                {-75, 92}, {-81, 1}
        };
        for (int[] nm : defaultNpcMaps) {
            knownNpcMap.put(nm[0], nm[1]);
        }

        // --- PRE-SEED TOAN BO QUAI & CAP DO CHUAN TU LV.1 DEN LV.135 (n0211.DEFAULT_LEVEL_SPOTS) ---
        int[][] defaultMobMapAndLv = new int[][]{
                {0, 0, 1}, {1, 0, 2}, {2, 3, 3}, {3, 3, 4}, {4, 4, 6}, {5, 4, 7},
                {6, 5, 9}, {7, 5, 10}, {8, 6, 10}, {9, 8, 12}, {10, 8, 14}, {11, 9, 16},
                {12, 9, 17}, {13, 12, 22}, {14, 12, 25}, {15, 13, 27}, {16, 15, 20},
                {17, 16, 22}, {18, 11, 20}, {19, 16, 23}, {20, 18, 25}, {21, 25, 27},
                {22, 25, 28}, {23, 26, 30}, {24, 26, 31}, {25, 27, 33}, {26, 27, 34},
                {27, 29, 36}, {28, 29, 37}, {29, 31, 40}, {30, 31, 41}, {31, 20, 43},
                {32, 20, 44}, {33, 19, 45}, {34, 23, 47}, {35, 23, 48}, {36, 24, 49},
                {37, 24, 50}, {38, 22, 52}, {39, 21, 55}, {40, 21, 56}, {41, 37, 58},
                {42, 38, 60}, {43, 39, 62}, {44, 40, 64}, {45, 41, 66}, {46, 42, 69},
                {47, 43, 72}, {48, 44, 75}, {49, 45, 78}, {50, 10, 19}, {51, 14, 29},
                {52, 17, 24}, {53, 28, 35}, {54, 32, 42}, {55, 47, 55}, {56, 51, 68},
                {57, 52, 79}, {58, 68, 82}, {59, 68, 83}, {60, 69, 85}, {61, 69, 86},
                {62, 70, 88}, {63, 70, 89}, {64, 63, 91}, {65, 63, 92}, {66, 64, 94},
                {67, 64, 95}, {68, 66, 97}, {69, 66, 98}, {70, 65, 100}, {71, 65, 101},
                {72, 71, 104}, {73, 72, 107}, {74, 73, 110}, {75, 74, 113}, {76, 75, 115},
                {77, 76, 117}, {78, 77, 119}, {79, 78, 121}, {80, 79, 124}, {81, 62, 80},
                {82, 94, 126}, {83, 95, 128}, {84, 96, 130}, {85, 97, 132}, {86, 98, 134},
                {87, 98, 135}
        };
        for (int[] m : defaultMobMapAndLv) {
            knownMobMap.put(m[0], m[1]);
            knownMobLevelMap.put(m[0], m[2]);
            mapObservedMaxMobLv.merge(m[1], m[2], Math::max);
        }
        knownMobNameMap.put(0, "C\u00e1o con");
        knownMobNameMap.put(2, "S\u00f3i x\u00e1m");
        knownMobNameMap.put(14, "C\u00e1o xanh");
        knownMobNameMap.put(86, "Qu\u00e1i Tuy\u1ebft");
        knownMobNameMap.put(87, " Chúa Tuy\u1ebft");
    }

    // --- QUAN LY TAM HOAN NHIEM VU & BO DEM NHAN/TRA RIENG BIET (Sửa B5, B6) ---
    private static final Map<Integer, Integer> questAcceptFailCounts = new ConcurrentHashMap<>();
    private static final Map<Integer, Integer> questTurnInFailCounts = new ConcurrentHashMap<>();
    private static final Map<Integer, Long> questLastInteractTime = new ConcurrentHashMap<>();
    private static final Map<Integer, Long> deferredQuestUntilMs = new ConcurrentHashMap<>();

    private static int trackedTargetMobId = -1;
    private static int trackedQuestId = -1;

    // --- KIEM SOAT DA DICH CHUYEN & CHONG LAP MAP (Sửa B12) ---
    private static int currentTripGoalMap = -1;
    private static int currentStoneTargetMap = -1;
    private static boolean useTeleportForCurrentTrip = true;
    private static long currentStoneAttemptStartTime = 0;
    private static final Map<Integer, Integer> mapVisitCountsInTrip = new ConcurrentHashMap<>();
    private static final Set<Integer> lockedTeleportMaps = Collections.newSetFromMap(new ConcurrentHashMap<>());
    private static final Map<Integer, Long> lockedTeleportUntilMs = new ConcurrentHashMap<>();

    // --- KIEM SOAT CAY LEVEL, PHAT CHET THEO QUEST & TRANG THAI MAX LEVEL (Sửa B4, B9, B11) ---
    private static int lastKnownPlayerLevel = -1;
    private static long lastLevelChangeTime = System.currentTimeMillis();
    private static long lastKnownPlayerExp = -1L;
    private static long lastExpChangeTime = System.currentTimeMillis();
    private static Field cachedPlayerExpField = null;
    private static final Map<Integer, Integer> questLevelWhenDied = new ConcurrentHashMap<>();
    private static final Map<Integer, Integer> questDeathCounts = new ConcurrentHashMap<>();
    private static long lastDeathTimeMs = 0;
    private static int mapBeforeDeath = -1;
    private static boolean needCheckNewSubQuestsAtHub = false;
    private static long hubSubQuestCheckStartTime = 0;
    private static long hubSubQuestArrivedTime = 0;
    private static boolean maxLevelCompletedSteadyMode = false;
    private static long lastSteadyQuestCheckTime = 0;
    private static final long MAX_LEVEL_NO_PROGRESS_MS = 15L * 60L * 1000L;

    // --- KIEM SOAT THUOC (HP/MP) & MUA THUOC / SAFE MODE (Sửa B1, B2) ---
    private static long lastHpPotionTime = 0;
    private static long lastMpPotionTime = 0;
    private static int hpBeforePotion = -1;
    private static int mpBeforePotion = -1;
    private static int hpPotBagCountBefore = -1;
    private static int mpPotBagCountBefore = -1;
    private static int consecutiveHpPotionNoEffect = 0;
    private static int consecutiveMpPotionNoEffect = 0;
    private static boolean outOfHpPotions = false;
    private static boolean outOfMpPotions = false;
    private static long lastPotionRestockAttemptTime = 0;
    private static int potionRestockTries = 0;
    private static boolean potionRestockFailedSafeMode = false;
    private static volatile int configuredPotionBuyQty = 50;

    private static final Set<Integer> VALID_WORLD_MAPS = new HashSet<>(Arrays.asList(
            0, 1, 2, 3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18, 19,
            20, 21, 22, 23, 24, 25, 26, 27, 28, 29, 30, 31, 32, 33, 34, 35, 37, 38,
            39, 40, 41, 42, 43, 44, 45, 47, 50, 51, 52, 62, 63, 64, 65, 66, 67, 68,
            69, 70, 71, 72, 73, 74, 75, 76, 77, 78, 79, 92, 93, 94, 95, 96, 97, 98
    ));

    private static final int[][] STORY_BRANCHES = new int[][]{
            {3, 4, 5, 6},
            {7, 8, 9, 10},
            {7, 11, 12, 13, 14},
            {7, 15, 16, 17},
            {7, 18, 25, 26, 27, 28},
            {33, 35, 29, 30, 31, 32},
            {33, 34, 20, 23, 24, 22, 21, 19, 47},
            {21, 37, 38, 39, 40, 41, 51},
            {22, 42, 43, 44, 45, 52, 62},
            {67, 68, 69, 70, 63, 64, 66, 65, 71, 72, 73},
            {73, 74, 75, 76, 77, 78, 79},
            {92, 93, 94, 95, 96, 97, 98}
    };

    private static final int[] HUNTING_MAP_ORDER = new int[]{
            3, 4, 5, 6, 7, 8, 9, 10, 11, 12, 13, 14, 15, 16, 17, 18,
            25, 26, 27, 28, 29, 30, 31, 32, 20, 23, 24, 22, 21, 19, 47,
            37, 38, 39, 40, 41, 51, 42, 43, 44, 45, 52, 62,
            68, 69, 70, 63, 64, 66, 65, 71, 72, 73, 74, 75, 76, 77, 78, 79,
            94, 95, 96, 97, 98
    };

    // =========================================================================
    // CACHE PHAN CHIEU (REFLECTION CACHE) GIAM TAI CPU CHO 100 TIEN TRINH
    // =========================================================================
    private static final class RefCache {
        final ClassLoader cl;
        Class<?> clsQuest;       // n0001.n0092
        Class<?> clsGameScr;     // n0001.n0041
        Class<?> clsMainObj;     // n0001.n0089
        Class<?> clsPlayer;      // n0001.n0133
        Class<?> clsMonsterBase; // n0001.n0088
        Class<?> clsGlobalSvc;   // n0001.n0044
        Class<?> clsHelper;      // n0001.n0213
        Class<?> clsLockMgr;     // n0001.n0207
        Class<?> clsToken;       // n0001.n0208
        Class<?> clsNav;         // n0001.n0193
        Class<?> clsRouteReq;    // n0001.n0199
        Class<?> clsRouteRes;    // n0001.n0197
        Class<?> clsNavStatus;   // n0001.n0198
        Class<?> clsMapHolder;   // n0001.n0245
        Class<?> clsFarm;        // n0001.n0211
        Class<?> clsText;        // n0001.n0158
        Class<?> clsCanvas;      // n0001.n0038
        Class<?> clsTField;      // n0001.n0155
        Class<?> clsLogin;       // n0001.n0068
        Class<?> clsVector;      // Lop mVector chuan cua game (lay tu fVecActors / fVecNew)

        // n0213
        Method mGetLevel;        // n0213.n0011_level()
        Method mGetMapId;        // n0213.n0004()
        Method mCombatAttack;    // n0213.n0001(n0208, n0089, int, String[])

        // n0207
        Method mGetLockMgr;      // n0207.n0001()
        Method mAcquireLock;     // n0207.n0001(String, int, long, String)
        Method mRenewLock;       // n0207.n0001(n0208, long)
        Method mReleaseLock;     // n0207.n0002(n0208)

        // n0193 & n0199 & n0197 & n0245
        Method mGetNav;          // n0193.n0001()
        Method mEnsureGraph;     // n0193.n0002()
        Method mStartRoute;      // n0193.n0001(n0199)
        Method mStopRoute;       // n0193.n0001(n0208, String)
        Method mGetNavStatus;    // n0193.n0003()
        Method mNavOnInGame;     // n0193.n0004()
        Method mNavTick;         // n0193.n0005()
        Method mNavOnMapChanged; // n0193.n0001(n0245, int)
        Method mNormText;        // n0193.n0012(String)
        Constructor<?> ctorRouteReq;
        Field fResOk;            // n0197.n0001
        Field fResMsg;           // n0197.n0002
        Constructor<?> ctorMapHolder;
        Method mSetMapHolderId;  // n0245.n0001(int)

        // n0211
        Field fDefaultSpots;     // n0211.DEFAULT_LEVEL_SPOTS
        Method mSelectSpotForLv; // n0211.n0001_selectSpotForLevel(int)

        // n0041 (GameScr)
        Field fPlayer;           // n0041.n0043
        Field fFocus;            // n0041.n0045
        Field fVecActors;        // n0041.n0046
        Method mFindObjScr;      // n0041.n0001(int, byte)

        // n0089 & n0133 & n0088
        Field fX;                // n0089.n0074
        Field fY;                // n0089.n0075
        Field fHp;               // n0089.n0093
        Field fMaxHp;            // n0089.n0094
        Field fMp;               // n0089.n0095
        Field fMaxMp;            // n0089.n0096
        Field fNpcId;            // n0089.n0147
        Field fObjType;          // n0089.n0148
        Field fObjName;          // n0089.n0155
        Field fState;            // n0089.n0159
        Field fIsDead;           // n0089.n0164
        Field fPlayerLvShort;    // n0133.n0100
        Field fMobTemplateId;    // n0088.n0043
        Method mGetMobTpl;       // n0088.n0003(int)

        // n0092 (Quest)
        Field fVecNew;           // n0092.n0041
        Field fVecFinish;        // n0092.n0042
        Field fVecDoingMain;     // n0092.n0043
        Field fVecDoingSub;      // n0092.n0044
        Field fQType;            // n0092.n0045
        Field fQId;              // n0092.n0046
        Field fQNpcAlt;          // n0092.n0048
        Field fQNpcId;           // n0092.n0049
        Field fQIsMain;          // n0092.n0050
        Field fQName;            // n0092.n0051
        Field fQShort;           // n0092.n0052
        Field fQTargets;         // n0092.n0055
        Field fQMaxCounts;       // n0092.n0056
        Field fQCurCounts;       // n0092.n0057
        Field fQHelp;            // n0092.n0060
        Method mQStartDialog;    // n0092.n0004()
        Method mQPerformStep;    // n0092.n0003(int, int)

        // n0044 (GlobalService)
        Method mGetSvc;          // n0044.n0002()
        Method mSendQuestPacket; // n0044.n0001(short, byte, byte)

        // n0158 & n0038
        Field fMapNames;         // n0158.n0395
        Field fLoadMap;          // n0038.n0017

        RefCache(ClassLoader cl) {
            this.cl = cl;
            init();
        }

        private void init() {
            try {
                clsQuest = findCls("n0001.n0092");
                clsGameScr = findCls("n0001.n0041");
                clsMainObj = findCls("n0001.n0089");
                clsPlayer = findCls("n0001.n0133");
                clsMonsterBase = findCls("n0001.n0088");
                clsGlobalSvc = findCls("n0001.n0044");
                clsHelper = findCls("n0001.n0213");
                clsLockMgr = findCls("n0001.n0207");
                clsToken = findCls("n0001.n0208");
                clsNav = findCls("n0001.n0193");
                clsRouteReq = findCls("n0001.n0199");
                clsRouteRes = findCls("n0001.n0197");
                clsNavStatus = findCls("n0001.n0198");
                clsMapHolder = findCls("n0001.n0245");
                clsFarm = findCls("n0001.n0211");
                clsText = findCls("n0001.n0158");
                clsCanvas = findCls("n0001.n0038");
                clsTField = findCls("n0001.n0155");
                clsLogin = findCls("n0001.n0068");

                if (clsHelper != null) {
                    mGetLevel = findMethod(clsHelper, "n0011_level");
                    mGetMapId = findMethod(clsHelper, "n0004");
                    if (clsToken != null && clsMainObj != null) {
                        mCombatAttack = findMethod(clsHelper, "n0001", clsToken, clsMainObj, int.class, String[].class);
                    }
                }
                if (clsLockMgr != null && clsToken != null) {
                    mGetLockMgr = findMethod(clsLockMgr, "n0001");
                    mAcquireLock = findMethod(clsLockMgr, "n0001", String.class, int.class, long.class, String.class);
                    mRenewLock = findMethod(clsLockMgr, "n0001", clsToken, long.class);
                    mReleaseLock = findMethod(clsLockMgr, "n0002", clsToken);
                }
                if (clsNav != null) {
                    mGetNav = findMethod(clsNav, "n0001");
                    mEnsureGraph = findMethod(clsNav, "n0002");
                    if (clsRouteReq != null) mStartRoute = findMethod(clsNav, "n0001", clsRouteReq);
                    if (clsToken != null) mStopRoute = findMethod(clsNav, "n0001", clsToken, String.class);
                    mGetNavStatus = findMethod(clsNav, "n0003");
                    mNavOnInGame = findMethod(clsNav, "n0004");
                    mNavTick = findMethod(clsNav, "n0005");
                    if (clsMapHolder != null) mNavOnMapChanged = findMethod(clsNav, "n0001", clsMapHolder, int.class);
                    mNormText = findMethod(clsNav, "n0012", String.class);
                }
                if (clsRouteReq != null && clsToken != null) {
                    try {
                        ctorRouteReq = clsRouteReq.getDeclaredConstructor(
                                String.class, int.class, boolean.class, int.class, int.class,
                                int.class, boolean.class, clsToken);
                        ctorRouteReq.setAccessible(true);
                    } catch (Throwable t) {
                        logReflectErrorOnce("n0199.<init>", t);
                    }
                }
                if (clsRouteRes != null) {
                    fResOk = findField(clsRouteRes, "n0001");
                    fResMsg = findField(clsRouteRes, "n0002");
                }
                if (clsMapHolder != null) {
                    try {
                        ctorMapHolder = clsMapHolder.getDeclaredConstructor();
                        ctorMapHolder.setAccessible(true);
                    } catch (Throwable t) {
                        logReflectErrorOnce("n0245.<init>", t);
                    }
                    mSetMapHolderId = findMethod(clsMapHolder, "n0001", int.class);
                }
                if (clsFarm != null) {
                    fDefaultSpots = findField(clsFarm, "DEFAULT_LEVEL_SPOTS");
                    mSelectSpotForLv = findMethod(clsFarm, "n0001_selectSpotForLevel", int.class);
                }
                if (clsGameScr != null) {
                    fPlayer = findField(clsGameScr, "n0043");
                    fFocus = findField(clsGameScr, "n0045");
                    fVecActors = findField(clsGameScr, "n0046");
                    mFindObjScr = findMethod(clsGameScr, "n0001", int.class, byte.class);
                }
                if (clsMainObj != null) {
                    fX = findField(clsMainObj, "n0074");
                    fY = findField(clsMainObj, "n0075");
                    fHp = findField(clsMainObj, "n0093");
                    fMaxHp = findField(clsMainObj, "n0094");
                    fMp = findField(clsMainObj, "n0095");
                    fMaxMp = findField(clsMainObj, "n0096");
                    fNpcId = findField(clsMainObj, "n0147");
                    fObjType = findField(clsMainObj, "n0148");
                    fObjName = findField(clsMainObj, "n0155");
                    fState = findField(clsMainObj, "n0159");
                    fIsDead = findField(clsMainObj, "n0164");
                }
                if (clsPlayer != null) {
                    fPlayerLvShort = findFieldHierarchy(clsPlayer, "n0100");
                }
                if (clsMonsterBase != null) {
                    fMobTemplateId = findField(clsMonsterBase, "n0043");
                    mGetMobTpl = findMethod(clsMonsterBase, "n0003", int.class);
                }
                if (clsQuest != null) {
                    fVecNew = findField(clsQuest, "n0041");
                    fVecFinish = findField(clsQuest, "n0042");
                    fVecDoingMain = findField(clsQuest, "n0043");
                    fVecDoingSub = findField(clsQuest, "n0044");
                    fQType = findField(clsQuest, "n0045");
                    fQId = findField(clsQuest, "n0046");
                    fQNpcAlt = findField(clsQuest, "n0048");
                    fQNpcId = findField(clsQuest, "n0049");
                    fQIsMain = findField(clsQuest, "n0050");
                    fQName = findField(clsQuest, "n0051");
                    fQShort = findField(clsQuest, "n0052");
                    fQTargets = findField(clsQuest, "n0055");
                    fQMaxCounts = findField(clsQuest, "n0056");
                    fQCurCounts = findField(clsQuest, "n0057");
                    fQHelp = findField(clsQuest, "n0060");
                    mQStartDialog = findMethod(clsQuest, "n0004");
                    mQPerformStep = findMethod(clsQuest, "n0003", int.class, int.class);
                }
                if (clsGlobalSvc != null) {
                    mGetSvc = findMethod(clsGlobalSvc, "n0002");
                    mSendQuestPacket = findMethod(clsGlobalSvc, "n0001", short.class, byte.class, byte.class);
                }
                if (clsText != null) {
                    fMapNames = findField(clsText, "n0395");
                }
                if (clsCanvas != null) {
                    fLoadMap = findField(clsCanvas, "n0017");
                }
                if (fVecActors != null) {
                    clsVector = fVecActors.getType();
                } else if (fVecNew != null) {
                    clsVector = fVecNew.getType();
                }
            } catch (Throwable t) {
                logReflectErrorOnce("RefCache.init", t);
            }
        }

        private Class<?> findCls(String name) {
            try {
                return Class.forName(name, false, cl);
            } catch (Throwable t) {
                logReflectErrorOnce("Class:" + name, t);
                return null;
            }
        }

        Field findField(Class<?> cls, String name) {
            if (cls == null) return null;
            try {
                Field f = cls.getDeclaredField(name);
                f.setAccessible(true);
                return f;
            } catch (Throwable t) {
                logReflectErrorOnce("Field:" + cls.getName() + "." + name, t);
                return null;
            }
        }

        private Field findFieldHierarchy(Class<?> cls, String name) {
            Class<?> cur = cls;
            while (cur != null && cur != Object.class) {
                try {
                    Field f = cur.getDeclaredField(name);
                    f.setAccessible(true);
                    return f;
                } catch (Throwable ignored) {
                    cur = cur.getSuperclass();
                }
            }
            logReflectErrorOnce("FieldHierarchy:" + (cls != null ? cls.getName() : "null") + "." + name,
                    new NoSuchFieldException(name));
            return null;
        }

        private Method findMethod(Class<?> cls, String name, Class<?>... params) {
            if (cls == null) return null;
            try {
                Method m = cls.getDeclaredMethod(name, params);
                m.setAccessible(true);
                return m;
            } catch (Throwable t) {
                logReflectErrorOnce("Method:" + cls.getName() + "." + name, t);
                return null;
            }
        }
    }

    private static volatile RefCache cachedRef = null;
    private static final Map<Class<?>, Method[]> vectorMethodsCache = new ConcurrentHashMap<>();
    private static Field cachedMobTplLevelField = null;
    private static Field cachedMobTplNameField = null;
    private static Field cachedLoadMapIdField = null;
    private static Field cachedLoadMapWField = null;
    private static Field cachedLoadMapHField = null;

    private static RefCache getRef(ClassLoader cl) {
        if (cl == null) return null;
        RefCache r = cachedRef;
        if (r == null || r.cl != cl || r.clsQuest == null) {
            synchronized (HeadlessJ2ME.class) {
                r = cachedRef;
                if (r == null || r.cl != cl || r.clsQuest == null) {
                    r = new RefCache(cl);
                    cachedRef = r;
                }
            }
        }
        return r;
    }

    private static void logReflectErrorOnce(String key, Throwable t) {
        if (loggedReflectionErrors.add(key)) {
            System.err.println("[AUTO-QUEST][REFLECT-WARN] Kh\u00f4ng t\u00ecm th\u1ea5y ho\u1eb7c l\u1ed7i ph\u1ea3n chi\u1ebfu t\u1ea1i "
                    + key + ": " + t.getClass().getSimpleName() + " - " + t.getMessage());
        }
    }

    private static void logRateLimited(String category, long minIntervalMs, String message) {
        long now = System.currentTimeMillis();
        Long prev = lastLogByCategory.get(category);
        if (prev == null || (now - prev) >= minIntervalMs) {
            lastLogByCategory.put(category, now);
            System.err.println(message);
        }
    }

    // =========================================================================
    // LUU & NAP DU LIEU DA HOC CHUNG GIUA CAC TIEN TRINH (ATOMIC JSON)
    // =========================================================================
    private static File getSharedKnowledgeFile() {
        try {
            if (loadedJarPath != null && !loadedJarPath.isEmpty()) {
                File jarF = new File(loadedJarPath).getAbsoluteFile();
                File parent = jarF.getParentFile();
                if (parent != null && parent.exists()) {
                    return new File(parent, "aq_world_knowledge.json");
                }
            }
        } catch (Throwable ignored) {}
        return new File("aq_world_knowledge.json");
    }

    private static void markTeleportMapLocked(int stoneMapId) {
        lockedTeleportMaps.add(stoneMapId);
        lockedTeleportUntilMs.put(stoneMapId, System.currentTimeMillis() + MAP_LOCK_TTL_MS);
    }

    private static void markMapSearchedEmpty(int mapId) {
        searchedEmptyMaps.add(mapId);
        searchedEmptyUntilMs.put(mapId, System.currentTimeMillis() + MAP_LOCK_TTL_MS);
    }

    private static void purgeExpiredLocksAndEmptyMaps(long now) {
        lockedTeleportUntilMs.entrySet().removeIf(e -> {
            if (now >= e.getValue()) {
                lockedTeleportMaps.remove(e.getKey());
                return true;
            }
            return false;
        });
        searchedEmptyUntilMs.entrySet().removeIf(e -> {
            if (now >= e.getValue()) {
                searchedEmptyMaps.remove(e.getKey());
                return true;
            }
            return false;
        });
    }

    private static synchronized void loadSharedWorldKnowledge() {
        File file = getSharedKnowledgeFile();
        if (!file.exists() || !file.isFile()) return;
        try {
            byte[] data = Files.readAllBytes(file.toPath());
            String json = new String(data, StandardCharsets.UTF_8);
            parseJsonIntMap(json, "knownNpcMap", knownNpcMap);
            parseJsonIntMap(json, "knownMobMap", knownMobMap);
            parseJsonIntMap(json, "knownMobLevelMap", knownMobLevelMap);
            parseJsonIntMap(json, "observedQuestMobLevel", observedQuestMobLevel);
            parseJsonIntMap(json, "mapObservedMaxMobLv", mapObservedMaxMobLv);
            parseJsonIntMap(json, "questOriginMap", questOriginMap);
            parseJsonIntSet(json, "mapsWithTeleportStone", mapsWithTeleportStone);
        } catch (Throwable t) {
            logReflectErrorOnce("loadSharedWorldKnowledge", t);
        }
    }
private static boolean setModNativeLevelFarm(RefCache ref, boolean enable, int playerLevel) {
        if (ref == null || ref.clsFarm == null) return false;
        try {
            if (enable) {
                // Nhả khóa của AUTO_QUEST trước để n0211 trong Mod toàn quyền điều khiển di chuyển & đánh quái
                releaseAllDirectionalKeys();
                releaseAutoRouteToken();
            }

            Object farmInst = null;
            // Tìm singleton instance của n0001.n0211 nếu có
            for (Method m : ref.clsFarm.getDeclaredMethods()) {
                if (Modifier.isStatic(m.getModifiers()) && m.getParameterCount() == 0
                        && m.getReturnType() == ref.clsFarm) {
                    m.setAccessible(true);
                    farmInst = invokeMethod(m, null);
                    break;
                }
            }
            if (farmInst == null) {
                for (Field f : ref.clsFarm.getDeclaredFields()) {
                    if (Modifier.isStatic(f.getModifiers()) && f.getType() == ref.clsFarm) {
                        f.setAccessible(true);
                        farmInst = f.get(null);
                        break;
                    }
                }
            }

            boolean invoked = false;
            // 1. Ưu tiên gọi các hàm start/stop hoặc setEnabled(boolean) trong n0001.n0211
            for (Method m : ref.clsFarm.getDeclaredMethods()) {
                m.setAccessible(true);
                String mName = m.getName().toLowerCase();
                Class<?>[] pt = m.getParameterTypes();
                Object target = Modifier.isStatic(m.getModifiers()) ? null : farmInst;
                if (!Modifier.isStatic(m.getModifiers()) && target == null) continue;

                if (pt.length == 1 && pt[0] == boolean.class) {
                    invokeMethod(m, target, enable);
                    invoked = true;
                } else if (enable && pt.length == 0 && (mName.contains("start") || mName.contains("enable") || mName.equals("n0002"))) {
                    if (m.getReturnType() == void.class || m.getReturnType() == boolean.class) {
                        invokeMethod(m, target);
                        invoked = true;
                    }
                } else if (!enable && pt.length == 0 && (mName.contains("stop") || mName.contains("disable") || mName.equals("n0003"))) {
                    if (m.getReturnType() == void.class || m.getReturnType() == boolean.class) {
                        invokeMethod(m, target);
                        invoked = true;
                    }
                }
            }

            // 2. Cập nhật cờ boolean bật/tắt trong n0001.n0211
            for (Field f : ref.clsFarm.getDeclaredFields()) {
                if (f.getType() == boolean.class) {
                    f.setAccessible(true);
                    String fn = f.getName().toLowerCase();
                    Object target = Modifier.isStatic(f.getModifiers()) ? null : farmInst;
                    if (!Modifier.isStatic(f.getModifiers()) && target == null) continue;
                    if (fn.contains("enable") || fn.contains("active") || fn.contains("run") || fn.equals("n0001") || fn.equals("n0002")) {
                        f.setBoolean(target, enable);
                        invoked = true;
                    }
                }
            }

            // 3. Gọi hàm tick/chọn bãi theo Level của n0211 nếu đang bật
            if (enable && ref.mSelectSpotForLv != null && playerLevel > 0) {
                try {
                    int[] spot = (int[]) invokeMethod(ref.mSelectSpotForLv, null, playerLevel);
                    if (spot != null && spot.length >= 3) {
                        currentSelectedFarmMobLv = spot[0];
                        currentTripGoalMap = spot[2];
                    }
                } catch (Throwable ignored) {}
            }

            modNativeLevelFarmActive = enable;
            return invoked;
        } catch (Throwable t) {
            logReflectErrorOnce("setModNativeLevelFarm", t);
            return false;
        }
    }

    /**
     * Nhận diện Boss để tuyệt đối không đánh nhầm khi treo cày Level dự phòng
     */
    private static boolean isLikelyBossMonster(RefCache ref, Object mobObj, int mobTemplateId, int currentMapId) {
        if (mobObj == null || ref == null) return false;
        try {
            // 1. Quái có HP tối đa cực lớn so với quái thường cùng cấp hoặc nằm ở phòng Boss
            int maxHp = (ref.fMaxHp != null) ? ref.fMaxHp.getInt(mobObj) : 0;
            if (maxHp >= 80000) return true;

            // 2. Kiểm tra tên quái có nằm trong danh sách Boss cốt truyện / Boss khu vực không
            String mobName = (ref.fObjName != null) ? (String) ref.fObjName.get(mobObj) : "";
            if (mobName == null || mobName.isEmpty()) {
                mobName = getMonsterTemplateName(ref, mobTemplateId);
            }
            String normMob = normalizeText(ref, mobName);
            if (normMob.contains("boss") || normMob.contains("chua") || normMob.contains("khonglo")
                    || normMob.contains("thuyentruong") || normMob.contains("kraken")
                    || normMob.contains("dracula") || normMob.contains("soiquy")
                    || normMob.contains("guardian") || normMob.contains("hoveda")
                    || normMob.contains("mummy") || normMob.contains("pharaoh")
                    || normMob.contains("vuaxacuop") || normMob.contains("tuongxuong")
                    || normMob.contains("tuthan") || normMob.contains("archangel")
                    || normMob.contains("cerberus") || normMob.contains("balrog")
                    || normMob.contains("medusa") || normMob.contains("chimera")
                    || normMob.contains("minotaur") || normMob.contains("yeti")
                    || normMob.contains("rongbang") || mobTemplateId == 5 || mobTemplateId == 11) {
                return true;
            }
        } catch (Throwable ignored) {}
        return false;
    }
    private static synchronized void saveSharedWorldKnowledgeIfNeeded(boolean force) {
        long now = System.currentTimeMillis();
        if (!force && (!knowledgeDirty || (now - lastKnowledgeSaveTime < 180000L))) return;
        lastKnowledgeSaveTime = now;
        knowledgeDirty = false;

        File target = getSharedKnowledgeFile();
        loadSharedWorldKnowledge();

        File tmp = new File(target.getParentFile(), target.getName() + ".tmp." + System.nanoTime());
        try {
            StringBuilder sb = new StringBuilder(1024);
            sb.append("{\n");
            appendJsonIntMap(sb, "knownNpcMap", knownNpcMap).append(",\n");
            appendJsonIntMap(sb, "knownMobMap", knownMobMap).append(",\n");
            appendJsonIntMap(sb, "knownMobLevelMap", knownMobLevelMap).append(",\n");
            appendJsonIntMap(sb, "observedQuestMobLevel", observedQuestMobLevel).append(",\n");
            appendJsonIntMap(sb, "mapObservedMaxMobLv", mapObservedMaxMobLv).append(",\n");
            appendJsonIntMap(sb, "questOriginMap", questOriginMap).append(",\n");
            appendJsonIntSet(sb, "mapsWithTeleportStone", mapsWithTeleportStone).append("\n");
            sb.append("}\n");

            try (FileOutputStream fos = new FileOutputStream(tmp)) {
                fos.write(sb.toString().getBytes(StandardCharsets.UTF_8));
                fos.getFD().sync();
            }
            try {
                Files.move(tmp.toPath(), target.toPath(),
                        StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (Throwable moveEx) {
                Files.move(tmp.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (Throwable t) {
            logReflectErrorOnce("saveSharedWorldKnowledge", t);
            try { if (tmp.exists()) tmp.delete(); } catch (Throwable ignored) {}
        }
    }

    private static StringBuilder appendJsonIntMap(StringBuilder sb, String key, Map<Integer, Integer> map) {
        sb.append("  \"").append(key).append("\": {");
        List<Integer> keys = new ArrayList<>(map.keySet());
        Collections.sort(keys);
        boolean first = true;
        for (Integer k : keys) {
            Integer v = map.get(k);
            if (k == null || v == null) continue;
            if (!first) sb.append(", ");
            first = false;
            sb.append("\"").append(k).append("\": ").append(v);
        }
        sb.append("}");
        return sb;
    }

    private static StringBuilder appendJsonIntSet(StringBuilder sb, String key, Set<Integer> set) {
        sb.append("  \"").append(key).append("\": [");
        List<Integer> vals = new ArrayList<>(set);
        Collections.sort(vals);
        for (int i = 0; i < vals.size(); i++) {
            if (i > 0) sb.append(", ");
            sb.append(vals.get(i));
        }
        sb.append("]");
        return sb;
    }

    private static void parseJsonIntMap(String json, String key, Map<Integer, Integer> out) {
        Pattern pBlock = Pattern.compile("\"" + Pattern.quote(key) + "\"\\s*:\\s*\\{([^}]*)\\}");
        Matcher mBlock = pBlock.matcher(json);
        if (!mBlock.find()) return;
        String body = mBlock.group(1);
        Pattern pEntry = Pattern.compile("\"(-?\\d+)\"\\s*:\\s*(-?\\d+)");
        Matcher mEntry = pEntry.matcher(body);
        while (mEntry.find()) {
            try {
                out.put(Integer.parseInt(mEntry.group(1)), Integer.parseInt(mEntry.group(2)));
            } catch (Throwable ignored) {}
        }
    }

    private static void parseJsonIntSet(String json, String key, Set<Integer> out) {
        Pattern pBlock = Pattern.compile("\"" + Pattern.quote(key) + "\"\\s*:\\s*\\[([^\\]]*)\\]");
        Matcher mBlock = pBlock.matcher(json);
        if (!mBlock.find()) return;
        String body = mBlock.group(1);
        Pattern pVal = Pattern.compile("-?\\d+");
        Matcher mVal = pVal.matcher(body);
        while (mVal.find()) {
            try {
                out.add(Integer.parseInt(mVal.group()));
            } catch (Throwable ignored) {}
        }
    }
public static void exitVM(int code) {
        try {
            shutdownTenant();
        } catch (Throwable ignored) {}
        try {
            Class<?> hostCls = Class.forName("MultiTenantHost");
            Method exitMethod = hostCls.getMethod("exitTenant", int.class);
            exitMethod.invoke(null, code);
            return;
        } catch (Throwable ignored) {}
        System.exit(code);
    }
    // =========================================================================
    // HAM MAIN & KHOI TAO HE THONG
    // =========================================================================
    public static void main(String[] args) {
        if (args.length < 3) exitVM(1);

        ipcOut = System.out;
        try {
            System.setErr(new PrintStream(System.err, true, "UTF-8"));
        } catch (Throwable ignored) {}
        System.setOut(System.err);

        String jarPath = args[0];
        loadedJarPath = jarPath;
        int width = Integer.parseInt(args[1]);
        int height = Integer.parseInt(args[2]);
        int fps = args.length >= 4 ? Integer.parseInt(args[3]) : 30;

        loadSharedWorldKnowledge();
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            releaseAllDirectionalKeys();
            saveSharedWorldKnowledgeIfNeeded(true);
        }, "AQ-ShutdownSave"));

        try {
            System.setProperty("microedition.platform", "MicroEmulator");
            System.setProperty("com.nokia.mid.ui.touch", "true");
            System.setProperty("microedition.profiles", "MIDP-2.0");
            System.setProperty("microedition.configuration", "CLDC-1.1");
            System.setProperty("microedition.locale", "vi-VN");
            System.setProperty("microedition.encoding", "UTF-8");

            File jarFile = new File(jarPath);
            if (!jarFile.exists()) exitVM(1);

            System.err.println("[VM] Kh\u1edfi t\u1ea1o MobilePlatform (" + width + "x" + height + " @ 30FPS)...");
            platform = new MobilePlatform(width, height);
            Mobile.setPlatform(platform);

            initDisplayAndConfig(width, height);

            platform.setPainter(() -> dirty = true);

            Thread inputThread = new Thread(HeadlessJ2ME::readInputLoop, "IPC-Input");
            inputThread.setDaemon(true);
            inputThread.start();

            Thread renderThread = new Thread(() -> renderLoop(fps), "IPC-Render");
            renderThread.setDaemon(true);
            renderThread.start();

            String jarUrl = jarFile.toURI().toURL().toString();
            System.err.println("[VM] \u0110ang n\u1ea1p JAR: " + jarFile.getName());

            boolean loaded = invokeLoadJar(platform, jarUrl);
            if (modNativeLevelFarmActive) {
                ClassLoader cl = getGameClassLoader();
                if (cl != null) setModNativeLevelFarm(getRef(cl), false, lastKnownPlayerLevel);
            }
            if (loaded) {
                postLoadConfigInit(width, height);
                System.err.println("[VM] N\u1ea1p JAR th\u00e0nh c\u00f4ng! \u0110ang kh\u1edfi ch\u1ea1y MIDlet 24/7 (30FPS)...");
                platform.runJar();
                dirty = true;

                Thread pcKeyUnlocker = new Thread(() -> {
                    while (running) {
                        try {
                            Thread.sleep(150);
                            ensurePcTFieldReady();
                        } catch (Throwable ignored) {}
                    }
                }, "IPC-UnlockPC");
                pcKeyUnlocker.setDaemon(true);
                pcKeyUnlocker.start();

                while (running) { Thread.sleep(1000); }
            } else {
                exitVM(1);
            }
        } catch (Throwable t) {
            System.err.println("[VM][FATAL] Loi khoi chay may ao: " + t);
            t.printStackTrace(System.err);
            exitVM(1);
        }
    }

    private static ClassLoader getGameClassLoader() {
        try {
            if (platform != null) {
                Field f = MobilePlatform.class.getDeclaredField("loader");
                f.setAccessible(true);
                Object l = f.get(platform);
                if (l instanceof ClassLoader) {
                    return (ClassLoader) l;
                }
            }
        } catch (Throwable ignored) {}
        return null;
    }

    private static synchronized List<String> getGameClassNames() {
        if (cachedGameClassNames != null) return cachedGameClassNames;
        List<String> list = new ArrayList<>();
        try (JarFile jf = new JarFile(loadedJarPath)) {
            Enumeration<JarEntry> entries = jf.entries();
            while (entries.hasMoreElements()) {
                JarEntry entry = entries.nextElement();
                String name = entry.getName();
                if (name != null && name.endsWith(".class")
                        && (name.startsWith("n0001/") || name.startsWith("com/silverknight/"))) {
                    list.add(name.substring(0, name.length() - 6).replace('/', '.'));
                }
            }
        } catch (Throwable ignored) {}
        cachedGameClassNames = list;
        return list;
    }

    private static List<Object> findAllActiveTFields(ClassLoader cl, Class<?> tfieldClass) {
        List<Object> results = new ArrayList<>();
        Set<Object> visited = Collections.newSetFromMap(new IdentityHashMap<>());

        for (String clsName : getGameClassNames()) {
            try {
                Class<?> cls = Class.forName(clsName, false, cl);
                for (Field f : cls.getDeclaredFields()) {
                    if (!Modifier.isStatic(f.getModifiers())) continue;
                    Class<?> ft = f.getType();
                    if (ft.isPrimitive() || ft == String.class) continue;
                    f.setAccessible(true);
                    Object val = f.get(null);
                    if (val != null) {
                        collectTFieldsRecursive(val, tfieldClass, results, visited, 0);
                    }
                }
            } catch (Throwable ignored) {}
        }
        return results;
    }

    private static void collectTFieldsRecursive(
            Object obj, Class<?> tfieldClass, List<Object> results, Set<Object> visited, int depth) {
        if (obj == null || depth > 3 || visited.contains(obj)) return;
        Class<?> cls = obj.getClass();
        String pkg = cls.getName();
        if (pkg.startsWith("java.lang.") || pkg.startsWith("java.awt.")
                || pkg.startsWith("javax.microedition.lcdui.Image")) {
            return;
        }
        visited.add(obj);

        if (tfieldClass.isInstance(obj)) {
            results.add(obj);
            return;
        }

        if (cls.isArray()) {
            if (cls.getComponentType().isPrimitive()) return;
            int len = Math.min(Array.getLength(obj), 30);
            for (int i = 0; i < len; i++) {
                collectTFieldsRecursive(Array.get(obj, i), tfieldClass, results, visited, depth + 1);
            }
            return;
        }

        if (obj instanceof Iterable<?>) {
            int count = 0;
            for (Object item : (Iterable<?>) obj) {
                if (count++ > 30) break;
                collectTFieldsRecursive(item, tfieldClass, results, visited, depth + 1);
            }
            return;
        }

        if (!pkg.startsWith("n0001.") && !pkg.startsWith("com.silverknight.")) return;

        while (cls != null && (cls.getName().startsWith("n0001.") || cls.getName().startsWith("com.silverknight."))) {
            for (Field f : cls.getDeclaredFields()) {
                if (Modifier.isStatic(f.getModifiers())) continue;
                Class<?> ft = f.getType();
                if (ft.isPrimitive() || ft == String.class) continue;
                try {
                    f.setAccessible(true);
                    Object child = f.get(obj);
                    if (child != null) {
                        collectTFieldsRecursive(child, tfieldClass, results, visited, depth + 1);
                    }
                } catch (Throwable ignored) {}
            }
            cls = cls.getSuperclass();
        }
    }

    private static boolean setTFieldValue(Object tf, String value) {
        if (tf == null) return false;
        try {
            Method setTextMethod = tf.getClass().getDeclaredMethod("n0002", String.class);
            setTextMethod.setAccessible(true);
            invokeMethod(setTextMethod, tf, value);
            return true;
        } catch (Throwable t1) {
            try {
                Field fText = tf.getClass().getDeclaredField("n0050");
                fText.setAccessible(true);
                fText.set(tf, value);
                return true;
            } catch (Throwable ignored) {}
        }
        return false;
    }

    private static boolean smartInjectTextToActiveField(String text, int targetIndex) {
        try {
            ClassLoader cl = getGameClassLoader();
            if (cl == null) return false;

            RefCache ref = getRef(cl);
            Class<?> tfieldClass = (ref != null && ref.clsTField != null)
                    ? ref.clsTField : Class.forName("n0001.n0155", false, cl);
            Field isFocusedField = tfieldClass.getDeclaredField("n0045");
            isFocusedField.setAccessible(true);

            List<Object> allFields = findAllActiveTFields(cl, tfieldClass);

            Object uTField = null;
            Object pTField = null;
            try {
                Class<?> loginClass = (ref != null && ref.clsLogin != null)
                        ? ref.clsLogin : Class.forName("n0001.n0068", false, cl);
                Field fUser = loginClass.getDeclaredField("n0043");
                Field fPass = loginClass.getDeclaredField("n0044");
                fUser.setAccessible(true);
                fPass.setAccessible(true);
                uTField = fUser.get(null);
                pTField = fPass.get(null);
            } catch (Throwable ignored) {}

            List<Object> focusedNonLogin = new ArrayList<>();
            List<Object> nonLoginFields = new ArrayList<>();

            for (Object tf : allFields) {
                boolean isLogin = (tf == uTField || tf == pTField);
                boolean focused = false;
                try { focused = isFocusedField.getBoolean(tf); } catch (Throwable ignored) {}

                if (!isLogin) {
                    nonLoginFields.add(tf);
                    if (focused) focusedNonLogin.add(tf);
                }
            }

            if (targetIndex >= 0 && targetIndex < nonLoginFields.size()) {
                Object chosen = nonLoginFields.get(targetIndex);
                if (setTFieldValue(chosen, text)) return true;
            }

            if (!focusedNonLogin.isEmpty()) {
                Object target = focusedNonLogin.get(focusedNonLogin.size() - 1);
                if (setTFieldValue(target, text)) return true;
            }

            if (!nonLoginFields.isEmpty()) {
                Object target = nonLoginFields.get(nonLoginFields.size() - 1);
                if (setTFieldValue(target, text)) {
                    try { isFocusedField.setBoolean(target, true); } catch (Throwable ignored) {}
                    return true;
                }
            }

            if (uTField != null && isFocusedField.getBoolean(uTField)) {
                return setTFieldValue(uTField, text);
            }
            if (pTField != null && isFocusedField.getBoolean(pTField)) {
                return setTFieldValue(pTField, text);
            }
        } catch (Throwable ignored) {}
        return false;
    }

    private static void tapKeyHelper(int keyCode, long holdMs, long waitAfterMs) {
        try {
            platform.keyPressed(keyCode);
            dirty = true;
            Thread.sleep(Math.min(holdMs, 60L));
        } catch (Throwable ignored) {
        } finally {
            try {
                platform.keyReleased(keyCode);
                dirty = true;
            } catch (Throwable ignored) {}
        }
        if (waitAfterMs > 0) {
            try { Thread.sleep(Math.min(waitAfterMs, 120L)); } catch (Throwable ignored) {}
        }
    }

    private static void releaseAllDirectionalKeys() {
        try {
            if (platform == null) return;
            if (activeDirectionalKey != 0) {
                platform.keyReleased(activeDirectionalKey);
                activeDirectionalKey = 0;
            }
            int[] dirs = {-1, -2, -3, -4, 50, 52, 54, 56};
            for (int k : dirs) {
                platform.keyReleased(k);
            }
            dirty = true;
        } catch (Throwable ignored) {}
    }

    // =========================================================================
    // HE THONG DINH TUYEN LIEN MAP, DA DICH CHUYEN & DO THI MAP
    // =========================================================================
    private static int[] getOrderedCandidateStonesForTarget(int targetMapId) {
        switch (targetMapId) {
            case 0: case 1: case 2: case 3: return new int[]{1};
            case 4: case 5: case 6: return new int[]{5, 1};
            case 7: return new int[]{1, 9, 11, 16};
            case 8: case 9: case 10: return new int[]{9, 1};
            case 11: return new int[]{11, 12, 13, 1};
            case 12: return new int[]{12, 11, 13, 1};
            case 13: case 14: return new int[]{13, 12, 11, 1};
            case 15: case 16: case 17: return new int[]{16, 1};
            case 18: case 25: case 26: case 27: case 28: return new int[]{25, 1};
            case 20: case 23: case 24: case 29: case 30: case 31: case 32: case 33: case 34: case 35:
                return new int[]{33, 19, 1};
            case 19: case 21: case 22: case 47: case 50:
                return new int[]{19, 33, 41, 45, 1};
            case 37: case 38: case 39: case 40: case 41: case 51:
                return new int[]{41, 19, 33, 1};
            case 42: case 43: case 44: case 45: case 52: case 62:
                return new int[]{45, 19, 33, 1};
            case 63: case 64: case 65: case 66: case 67: case 68: case 69: case 70: case 71: case 72:
                return new int[]{67, 73, 19, 33, 1};
            case 73: case 74: case 75: case 76: case 77: case 78: case 79:
                return new int[]{73, 67, 19, 33, 1};
            case 92: case 93: case 94: case 95: case 96: case 97: case 98:
                return new int[]{92, 67, 19, 33, 1};
            default: return new int[]{1};
        }
    }

    private static boolean isDirectlyConnectedWalkMap(int mapA, int mapB) {
        if (mapA == mapB) return true;
        if ((mapA == 1 && (mapB == 0 || mapB == 2 || mapB == 3 || mapB == 7))
                || (mapB == 1 && (mapA == 0 || mapA == 2 || mapA == 3 || mapA == 7))) return true;
        if ((mapA == 7 && (mapB == 8 || mapB == 11 || mapB == 15 || mapB == 18))
                || (mapB == 7 && (mapA == 8 || mapA == 11 || mapA == 15 || mapA == 18))) return true;
        if ((mapA == 18 && mapB == 25) || (mapA == 25 && mapB == 18)) return true;
        if ((mapA == 19 && (mapB == 21 || mapB == 22 || mapB == 47 || mapB == 50))
                || (mapB == 19 && (mapA == 21 || mapA == 22 || mapA == 47 || mapA == 50))) return true;
        for (int[] branch : STORY_BRANCHES) {
            for (int i = 0; i < branch.length - 1; i++) {
                if ((branch[i] == mapA && branch[i + 1] == mapB)
                        || (branch[i] == mapB && branch[i + 1] == mapA)) return true;
            }
        }
        return false;
    }

    private static int selectBestTeleportStoneForTrip(int currentMapId, int targetMapId) {
        if (!useTeleportForCurrentTrip || currentMapId == targetMapId) return -1;
        if (!mapsWithTeleportStone.contains(currentMapId)) return -1;
        if (isDirectlyConnectedWalkMap(currentMapId, targetMapId) && !mapsWithTeleportStone.contains(targetMapId)) {
            useTeleportForCurrentTrip = false;
            return -1;
        }
        int[] candidates = getOrderedCandidateStonesForTarget(targetMapId);
        for (int stoneMap : candidates) {
            if (stoneMap == currentMapId) {
                useTeleportForCurrentTrip = false;
                return -1;
            }
            // Khong chon tram Da dich chuyen neu nhan vat chua du Level vao map do
            int reqStoneLv = getMinLevelForSpecialMap(stoneMap);
            if (reqStoneLv > 0 && lastKnownPlayerLevel > 0 && lastKnownPlayerLevel < reqStoneLv) {
                continue;
            }
            int visits = mapVisitCountsInTrip.getOrDefault(stoneMap, 0);
            if (!lockedTeleportMaps.contains(stoneMap) && visits == 0) {
                return stoneMap;
            }
        }
        useTeleportForCurrentTrip = false;
        return -1;
    }

    private static void resetTeleportTripState(int newGoalMapId, int currentMapId) {
        currentTripGoalMap = newGoalMapId;
        currentStoneTargetMap = -1;
        useTeleportForCurrentTrip = true;
        currentStoneAttemptStartTime = System.currentTimeMillis();
        mapVisitCountsInTrip.clear();
        if (currentMapId >= 0) {
            mapVisitCountsInTrip.put(currentMapId, 1);
            if (mapsWithTeleportStone.contains(currentMapId)) {
                lockedTeleportMaps.remove(currentMapId);
            }
        }
    }

    private static int getQuestHubForPlayerLevel(int playerLevel, int currentMapId) {
        int desiredHub = 1;
        if (playerLevel >= 80 && !lockedTeleportMaps.contains(67)) desiredHub = 67;
        else if (playerLevel >= 40 && !lockedTeleportMaps.contains(33)) desiredHub = 33;
        else if (playerLevel >= 35 && !lockedTeleportMaps.contains(19)) desiredHub = 19;
        return desiredHub;
    }

    // =========================================================================
    // VONG LAP DON LUONG THICH UNG (SINGLE-THREADED ADAPTIVE EXECUTOR)
    // =========================================================================
    private static synchronized void startAutoQuestLoop() {
        long now = System.currentTimeMillis();
        currentMapEnterTime = now;
        lastTargetSeenOrQuestChangeTime = now;
        currentStoneAttemptStartTime = now;
        lastPositionChangeTime = now;
        lastRouteTime = 0;
        currentRoutingTargetMap = -1;
        lastRoutedFromMapId = -1;
        lockedTargetMob = null;
        approachTrackedMob = null;
        if (autoQuestThread != null && autoQuestThread.isAlive()) return;
        autoQuestThread = new Thread(() -> {
            System.err.println("[AUTO-QUEST] Kh\u1edfi ch\u1ea1y lu\u1ed3ng \u0111\u01a1n T\u1ef1 \u0110\u1ed9ng L\u00e0m Nhi\u1ec7m V\u1ee5 (B\u1ed9 l\u1ecdc: "
                    + autoQuestFilter + " | T\u1ef1 d\u00f9ng thu\u1ed1c: " + autoPotionEnabled + ")");
            while (running) {
                try {
                    boolean doStep = false;
                    if (stepRequested) {
                        stepRequested = false;
                        doStep = true;
                    }
                    if (questRecordEnabled) {
                        synchronized (TICK_LOCK) {
                            recordQuestSnapshot();
                        }
                    }
                    if (!autoQuestEnabled && !doStep) {
                        lockedTargetMob = null;
                        releaseAllDirectionalKeys();
                        releaseAutoRouteToken();
                        Thread.sleep(450);
                        continue;
                    }
                    long sleepMs;
                    synchronized (TICK_LOCK) {
                        sleepMs = runAutoQuestTick();
                    }
                    saveSharedWorldKnowledgeIfNeeded(false);
                    Thread.sleep(Math.max(120L, Math.min(sleepMs, 1500L)));
                } catch (Throwable t) {
                    try { Thread.sleep(400); } catch (InterruptedException ignored) {}
                }
            }
        }, "IPC-AutoQuest");
        autoQuestThread.setDaemon(true);
        autoQuestThread.start();
    }

    // =========================================================================
    // P0-2: XIN & GIA HAN TOKEN HOP LE (PRIORITY 1..2000, TTL 1000..60000)
    // =========================================================================
    private static boolean isExternalModServiceHoldingLock(RefCache ref) {
        if (ref == null || ref.clsLockMgr == null || ref.mGetLockMgr == null || ref.clsToken == null) return false;
        try {
            Object lockMgr = invokeMethod(ref.mGetLockMgr, null);
            if (lockMgr == null) return false;
            if (cachedLockMgrTokenField == null) {
                for (Field f : ref.clsLockMgr.getDeclaredFields()) {
                    if (ref.clsToken.isAssignableFrom(f.getType())) {
                        f.setAccessible(true);
                        cachedLockMgrTokenField = f;
                        break;
                    }
                }
            }
            if (cachedLockMgrTokenField != null) {
                Object activeTok = cachedLockMgrTokenField.get(lockMgr);
                if (activeTok != null && activeTok != autoQuestRouteToken) {
                    if (cachedTokenOwnerField == null) {
                        for (Field tf : ref.clsToken.getDeclaredFields()) {
                            if (tf.getType() == String.class) {
                                tf.setAccessible(true);
                                cachedTokenOwnerField = tf;
                                break;
                            }
                        }
                    }
                    String owner = (cachedTokenOwnerField != null) ? (String) cachedTokenOwnerField.get(activeTok) : "";
                    if (owner == null || !"AUTO_QUEST".equals(owner)) {
                        return true;
                    }
                }
            }
        } catch (Throwable ignored) {}
        return false;
    }

    private static Object ensureControlToken(RefCache ref) {
        if (ref == null || ref.clsLockMgr == null || ref.mGetLockMgr == null) return null;
        long now = System.currentTimeMillis();
        if (now < lastNavRejectBackoffUntil || isAutoSellingSession) return null;

        try {
            if (isExternalModServiceHoldingLock(ref)) {
                autoQuestRouteToken = null;
                currentRoutingTargetMap = -1;
                lastNavRejectBackoffUntil = now + 1500L;
                return null;
            }

            Object lockMgr = invokeMethod(ref.mGetLockMgr, null);
            if (lockMgr == null) return null;

            // 1. Gia han token voi TTL ngan (6000ms) neu dang giu
            if (autoQuestRouteToken != null && ref.mRenewLock != null) {
                try {
                    Boolean ok = (Boolean) invokeMethod(ref.mRenewLock, lockMgr, autoQuestRouteToken, 6000L);
                    if (Boolean.TRUE.equals(ok)) {
                        return autoQuestRouteToken;
                    }
                } catch (Throwable ignored) {}
                // Neu gia han that bai -> Dich vu uu tien cao hon (Auto Ban Do cua Mod) vua chiem quyen
                autoQuestRouteToken = null;
                currentRoutingTargetMap = -1;
                lastNavRejectBackoffUntil = now + 2000L;
                return null;
            }

            // 2. Xin moi voi priority = 1 (thap nhat) de bat ky chuc nang nao trong Mod cung de dang chiem quyen
            if (ref.mAcquireLock != null) {
                Object tok = invokeMethod(ref.mAcquireLock, lockMgr, "AUTO_QUEST", 1, 6000L, "AutoQuest");
                if (tok != null) {
                    currentControlPriority = 1;
                    autoQuestRouteToken = tok;
                    return autoQuestRouteToken;
                }
            }

            lastNavRejectBackoffUntil = now + 2000L;
            logRateLimited("TOKEN_YIELD_MOD", 5000L,
                    "[AUTO-QUEST] \u0110ang nh\u01b0\u1eddng quy\u1ec1n \u0111i\u1ec1u khi\u1ec3n n0208 cho ch\u1ee9c n\u0103ng c\u00f3 s\u1eb5n trong Mod (T\u1ef1 \u0111\u1ed9ng b\u00e1n \u0111\u1ed3 / Mua thu\u1ed1c)...");
        } catch (Throwable t) {
            logReflectErrorOnce("ensureControlToken", t);
        }
        return null;
    }

    private static void stopNavOnly(RefCache ref) {
        try {
            releaseAllDirectionalKeys();
            if (ref == null || ref.mGetNav == null || ref.mStopRoute == null || autoQuestRouteToken == null) return;
            Object navInstance = invokeMethod(ref.mGetNav, null);
            if (navInstance != null) {
                invokeMethod(ref.mStopRoute, navInstance, autoQuestRouteToken, "T\u1edbi m\u1ee5c ti\u00eau");
            }
        } catch (Throwable ignored) {}
    }

    private static void releaseAutoRouteToken() {
        try {
            ClassLoader cl = getGameClassLoader();
            if (cl == null || autoQuestRouteToken == null) return;
            RefCache ref = getRef(cl);
            stopNavOnly(ref);
            if (ref != null && ref.mGetLockMgr != null && ref.mReleaseLock != null) {
                Object lockMgr = invokeMethod(ref.mGetLockMgr, null);
                if (lockMgr != null) {
                    invokeMethod(ref.mReleaseLock, lockMgr, autoQuestRouteToken);
                }
            }
        } catch (Throwable ignored) {
        } finally {
            autoQuestRouteToken = null;
            currentRoutingTargetMap = -1;
            currentRoutingTargetX = -1;
            currentRoutingTargetY = -1;
            lastRoutedFromMapId = -1;
        }
    }

    // =========================================================================
    // P2-10: TU DUNG THUOC CO COOLDOWN & PHAT HIEN HET THUOC
    // =========================================================================
    private static int[] countPotionsInBag(RefCache ref, Object player) {
        try {
            Object vecBag = resolvePlayerInventoryVector(ref, player);
            if (vecBag == null) return new int[]{-1, -1};
            Method[] vm = getVectorMethods(vecBag);
            if (vm == null) return new int[]{-1, -1};
            int sz = (Integer) invokeMethod(vm[0], vecBag);
            int hpSlots = 0;
            int mpSlots = 0;
            for (int i = 0; i < sz; i++) {
                Object raw = invokeMethod(vm[1], vecBag, i);
                BagItemEntry e = parseBagItemEntry(ref, raw, i);
                if (e == null || e.category != 4) continue;
                String norm = normalizeText(ref, e.name);
                if (norm.contains("mau") || norm.contains("hp") || norm.contains("hong")) {
                    hpSlots++;
                } else if (norm.contains("nangluong") || norm.contains("mp") || norm.contains("xanh")) {
                    mpSlots++;
                } else {
                    hpSlots++;
                    mpSlots++;
                }
            }
            return new int[]{hpSlots, mpSlots};
        } catch (Throwable ignored) {
            return new int[]{-1, -1};
        }
    }

    private static void checkAndAutoPotion(RefCache ref, Object player, int currentMapId) {
        if (ref == null || player == null || ref.fHp == null || ref.fMaxHp == null)
            return;
        try {
            long now = System.currentTimeMillis();
            int hp = ref.fHp.getInt(player);
            int maxHp = ref.fMaxHp.getInt(player);
            int mp = (ref.fMp != null) ? ref.fMp.getInt(player) : 100;
            int maxMp = (ref.fMaxMp != null) ? ref.fMaxMp.getInt(player) : 100;

            int[] potCounts = countPotionsInBag(ref, player);
            int curHpPots = potCounts[0];
            int curMpPots = potCounts[1];

            if (curHpPots > 0 && outOfHpPotions) {
                outOfHpPotions = false;
                consecutiveHpPotionNoEffect = 0;
                potionRestockFailedSafeMode = false;
            }
            if (curMpPots > 0 && outOfMpPotions) {
                outOfMpPotions = false;
                consecutiveMpPotionNoEffect = 0;
                potionRestockFailedSafeMode = false;
            }

            // [B2] Kiểm tra kết quả lần bấm thuốc HP trước đó (loại trừ dương tính giả khi đang bị quái đánh tụt máu)
            if (hpBeforePotion > 0 && (now - lastHpPotionTime >= 900L) && (now - lastHpPotionTime <= 2500L)) {
                boolean tookDamageInCombat = (hp < hpBeforePotion) && (lockedTargetMob != null);
                boolean bagConfirmedEmpty = (curHpPots == 0);
                boolean bagDecreased = (curHpPots >= 0 && hpPotBagCountBefore > 0 && curHpPots < hpPotBagCountBefore);

                if (bagConfirmedEmpty
                        || (!tookDamageInCombat && hp <= hpBeforePotion && !bagDecreased && hp < (maxHp * 45 / 100))) {
                    if (!tookDamageInCombat)
                        consecutiveHpPotionNoEffect++;
                    int threshold = (curHpPots < 0) ? 6 : 3;
                    if (bagConfirmedEmpty || consecutiveHpPotionNoEffect >= threshold) {
                        outOfHpPotions = true;
                        logRateLimited("POTION_HP_EMPTY", 10000L,
                                "[AUTO-POTION] X\u00e1c nh\u1eadn h\u1ebft b\u00ecnh m\u00e1u (HP bag slots="
                                        + curHpPots
                                        + ", noEffect=" + consecutiveHpPotionNoEffect
                                        + ") -> T\u1ea1m ng\u01b0ng b\u1ea5m ph\u00edm 7.");
                    }
                } else if (hp > hpBeforePotion || bagDecreased) {
                    consecutiveHpPotionNoEffect = 0;
                    outOfHpPotions = false;
                }
                hpBeforePotion = -1;
            }

            if (!outOfHpPotions && maxHp > 50 && hp > 0 && hp < (maxHp * 45 / 100)
                    && (now - lastHpPotionTime >= 1500L)) {
                hpBeforePotion = hp;
                hpPotBagCountBefore = curHpPots;
                lastHpPotionTime = now;
                tapKeyHelper(55, 25, 40); // Phim 7 (HP)
            }

            // [B2] Kiểm tra kết quả lần bấm thuốc MP trước đó
            if (mpBeforePotion > 0 && (now - lastMpPotionTime >= 900L) && (now - lastMpPotionTime <= 2500L)) {
                boolean spentManaInCombat = (mp < mpBeforePotion) && (lockedTargetMob != null);
                boolean bagConfirmedEmpty = (curMpPots == 0);
                boolean bagDecreased = (curMpPots >= 0 && mpPotBagCountBefore > 0 && curMpPots < mpPotBagCountBefore);

                if (bagConfirmedEmpty
                        || (!spentManaInCombat && mp <= mpBeforePotion && !bagDecreased && mp < (maxMp * 40 / 100))) {
                    if (!spentManaInCombat)
                        consecutiveMpPotionNoEffect++;
                    int threshold = (curMpPots < 0) ? 6 : 3;
                    if (bagConfirmedEmpty || consecutiveMpPotionNoEffect >= threshold) {
                        outOfMpPotions = true;
                        logRateLimited("POTION_MP_EMPTY", 10000L,
                                "[AUTO-POTION] X\u00e1c nh\u1eadn h\u1ebft b\u00ecnh n\u0103ng l\u01b0\u1ee3ng (MP bag slots="
                                        + curMpPots
                                        + ", noEffect=" + consecutiveMpPotionNoEffect
                                        + ") -> T\u1ea1m ng\u01b0ng b\u1ea5m ph\u00edm 9.");
                    }
                } else if (mp > mpBeforePotion || bagDecreased) {
                    consecutiveMpPotionNoEffect = 0;
                    outOfMpPotions = false;
                }
                mpBeforePotion = -1;
            }

            if (!outOfMpPotions && maxMp > 50 && mp < (maxMp * 40 / 100) && (now - lastMpPotionTime >= 1500L)) {
                mpBeforePotion = mp;
                mpPotBagCountBefore = curMpPots;
                lastMpPotionTime = now;
                tapKeyHelper(57, 25, 40); // Phim 9 (MP)
            }
        } catch (Throwable ignored) {
        }
    }
    /**
     * [B1] Chu trình về thị trấn mua thuốc hoặc chuyển sang chế độ an toàn (Safe Mode) khi hết thuốc.
     * UNVERIFIED NOTE: Packet mua thuốc trực tiếp trong n0001.n0044 phụ thuộc vào menu index động từ Server.
     * Không đoán bừa packet; bot đi về Hub, mở hội thoại NPC -3 (Lisa) / nhường khóa cho Mod,
     * nếu sau 3 lần thử vẫn hết thuốc thì dừng sạch và chuyển sang Safe Mode (cày bãi -3 Lv, không đánh Boss).
     */
    private static boolean handleOutOfPotionsRestockOrSafeMode(
            RefCache ref, Object player, int currentMapId, int mobCountOnMap, int playerLevel) {
        if (!outOfHpPotions && !outOfMpPotions) {
            potionRestockTries = 0;
            potionRestockFailedSafeMode = false;
            return false;
        }

        long now = System.currentTimeMillis();
        // Sau 60s tam ngung bam phim 7/9, tu dong reset de thu bam thuoc lai (tranh duong tinh gia khi dang mo hoi thoai NPC)
        if (outOfHpPotions && (now - lastHpPotionTime > 60000L)) {
            outOfHpPotions = false;
            consecutiveHpPotionNoEffect = 0;
        }
        if (outOfMpPotions && (now - lastMpPotionTime > 60000L)) {
            outOfMpPotions = false;
            consecutiveMpPotionNoEffect = 0;
        }

        int[] potCounts = countPotionsInBag(ref, player);
        if (potCounts[0] > 0) {
            outOfHpPotions = false;
            consecutiveHpPotionNoEffect = 0;
        }
        if (potCounts[1] > 0) {
            outOfMpPotions = false;
            consecutiveMpPotionNoEffect = 0;
        }

        // Khong chiem quyen dieu khien cua Auto Quest de tranh ket Safe Mode khi chua co shop packet
        return false;
    }
    // UNVERIFIED: Thu mo tiem thuoc tai Lang khi het thuoc (neu nhan vat dung gan chu tiem NPC -3 / Lisa)
    private static boolean tryInteractPotionNpcInTown(RefCache ref, Object player, int currentMapId) {
        try {
            Object npcLisa = findNpcOnCurrentMap(ref, -3);
            if (npcLisa == null) npcLisa = findNpcOnCurrentMap(ref, -4);
            if (npcLisa != null && ref.fFocus != null && player != null) {
                int px = ref.fX.getInt(player);
                int py = ref.fY.getInt(player);
                int nx = ref.fX.getInt(npcLisa);
                int ny = ref.fY.getInt(npcLisa);
                if (Math.hypot(nx - px, ny - py) > 48) {
                    movePlayerCloseToTarget(ref, npcLisa, player, currentMapId);
                    return false;
                }
                stopNavOnly(ref);
                ref.fFocus.set(null, npcLisa);
                releaseAutoRouteToken();
                long now = System.currentTimeMillis();
                if (now - lastPotionRestockAttemptTime >= 2500L) {
                    lastPotionRestockAttemptTime = now;
                    potionRestockTries++;
                    tapKeyHelper(-5, 35, 80);
                    tapKeyHelper(-7, 35, 60);
                    logRateLimited("POTION_RESTOCK", 4000L,
                            "[AUTO-POTION] \u0110\u00e3 m\u1edf NPC ti\u1ec7m thu\u1ed1c t\u1ea1i Map " + currentMapId
                                    + " (l\u1ea7n #" + potionRestockTries + ", SL c\u1ea5u h\u00ecnh=" + configuredPotionBuyQty + ").");
                }
            } else {
                potionRestockTries++;
            }
        } catch (Throwable ignored) {}
        return false;
    }

    // =========================================================================
    // CHIEN DAU VOI TOKEN HOP LE & XOAY VONG KY NANG
    // =========================================================================
    private static void performFullSkillCombo(RefCache ref, Object targetMob) {
        Object token = ensureControlToken(ref);
        if (token == null) return; // Tuyet doi khong nha phim hoac bam skill khi Mod dang dieu khien ve ban do
        releaseAllDirectionalKeys();
        try {
            if (ref.mCombatAttack != null) {
                Boolean combatOk = (Boolean) invokeMethod(ref.mCombatAttack, null, token, targetMob, 0, FULL_SKILL_PRIORITY);
                if (Boolean.TRUE.equals(combatOk)) {
                    dirty = true;
                }
            }
        } catch (Throwable t) {
            logReflectErrorOnce("performFullSkillCombo", t);
        }

        long now = System.currentTimeMillis();
        if (now - lastAttackKeyTime >= 240L) {
            lastAttackKeyTime = now;
            int skillKey1 = SKILL_KEY_ROTATION[currentSkillRotationIdx % SKILL_KEY_ROTATION.length];
            currentSkillRotationIdx = (currentSkillRotationIdx + 1) % SKILL_KEY_ROTATION.length;
            int skillKey2 = SKILL_KEY_ROTATION[currentSkillRotationIdx % SKILL_KEY_ROTATION.length];
            currentSkillRotationIdx = (currentSkillRotationIdx + 1) % SKILL_KEY_ROTATION.length;

            tapKeyHelper(skillKey1, 20, 20);
            if (skillKey2 != skillKey1) tapKeyHelper(skillKey2, 20, 20);
            if (skillKey1 != 49 && skillKey2 != 49) tapKeyHelper(49, 20, 15);
        }
    }

    // =========================================================================
    // P0-1: DOC MAP ID & LEVEL NHAN VAT CHINH XAC TU BYTECODE n0213
    // =========================================================================
    private static int getCurrentMapId(RefCache ref) {
        if (ref == null) return -1;
        try {
            if (ref.mGetMapId != null) {
                int mapId = (Integer) invokeMethod(ref.mGetMapId, null);
                if (mapId >= 0) return mapId;
            }
        } catch (Throwable ignored) {}
        try {
            if (ref.fLoadMap != null) {
                Object loadMap = ref.fLoadMap.get(null);
                if (loadMap != null) {
                    if (cachedLoadMapIdField == null) {
                        cachedLoadMapIdField = loadMap.getClass().getDeclaredField("n0004");
                        cachedLoadMapIdField.setAccessible(true);
                    }
                    return cachedLoadMapIdField.getInt(loadMap);
                }
            }
        } catch (Throwable ignored) {}
        return -1;
    }

    /**
     * P0-1 Fix: Dung n0213.n0011_level() tra ve n0041.n0043.n0100 & 0xFF (hoac -1 khi chua vao game).
     * Fallback truc tiep sang field short n0100 cua n0133. Tuyet doi khong doan n0149.
     */
    private static int getPlayerLevel(RefCache ref, Object player) {
        if (ref == null || player == null) return -1;
        try {
            if (ref.mGetLevel != null) {
                int lv = (Integer) invokeMethod(ref.mGetLevel, null);
                if (lv >= 1) return lv;
                if (lv == -1) return -1;
            }
        } catch (Throwable t) {
            logReflectErrorOnce("n0213.n0011_level", t);
        }
        try {
            if (ref.fPlayerLvShort != null) {
                int lv = ref.fPlayerLvShort.getShort(player) & 0xFF;
                if (lv >= 1) return lv;
            }
        } catch (Throwable t) {
            logReflectErrorOnce("n0133.n0100", t);
        }
        return lastKnownPlayerLevel > 0 ? lastKnownPlayerLevel : 1;
    }

    private static boolean isTownMainNpc(int npcId) {
        // Khong gop chung -7 (Aman Map 1 / Amin Map 33) vao day de tranh keo nham chuong cot truyen
        return npcId == -2 || npcId == -3 || npcId == -4 || npcId == -5 || npcId == -8;
    }


    private static Method[] getVectorMethods(Object vecObj) {
        if (vecObj == null) return null;
        Class<?> cls = vecObj.getClass();
        if (NON_VECTOR_CLASSES.contains(cls)) return null;
        RefCache ref = cachedRef;
        if (ref != null && ref.clsVector != null && !ref.clsVector.isAssignableFrom(cls)) {
            NON_VECTOR_CLASSES.add(cls);
            return null;
        }
        Method[] cached = vectorMethodsCache.get(cls);
        if (cached != null) return cached;
        try {
            Method mSize = cls.getDeclaredMethod("n0003");
            Method mElem = cls.getDeclaredMethod("n0001", int.class);
            if (mSize.getReturnType() != int.class) {
                NON_VECTOR_CLASSES.add(cls);
                return null;
            }
            mSize.setAccessible(true);
            mElem.setAccessible(true);
            Method[] pair = new Method[]{mSize, mElem};
            vectorMethodsCache.put(cls, pair);
            return pair;
        } catch (Throwable t) {
            NON_VECTOR_CLASSES.add(cls);
            return null;
        }
    }

    private static int recordActorsOnCurrentMap(RefCache ref, Object player, int currentMapId) {
        if (ref == null || currentMapId < 0 || (System.currentTimeMillis() - currentMapEnterTime < 200L)) return 0;

        if (!mapNamesLoggedOnce && ref.fMapNames != null) {
            try {
                String[] names = (String[]) ref.fMapNames.get(null);
                if (names != null && names.length > 0) {
                    mapNamesLoggedOnce = true;
                    StringBuilder sb = new StringBuilder("[AUTO-QUEST] B\u1ea3ng t\u00ean Map (n0158.n0395, t\u1ed5ng=" + names.length + "): ");
                    for (int i = 0; i < names.length; i++) {
                        if (names[i] != null && !names[i].isEmpty()) {
                            sb.append(i).append("=").append(names[i]).append(" | ");
                        }
                    }
                    System.err.println(sb.toString());
                }
            } catch (Throwable ignored) {}
        }

        int mobCountOnMap = 0;
        try {
            Object vecActors = (ref.fVecActors != null) ? ref.fVecActors.get(null) : null;
            if (vecActors == null) return 0;

            Method[] vm = getVectorMethods(vecActors);
            if (vm == null) return 0;

            int count = (Integer) invokeMethod(vm[0], vecActors);
            List<String> mobSummary = new ArrayList<>();
            List<String> npcSummary = new ArrayList<>();
            Set<Integer> seenMobs = new HashSet<>();
            Set<Integer> seenNpcs = new HashSet<>();

            for (int i = 0; i < count; i++) {
                Object obj = invokeMethod(vm[1], vecActors, i);
                if (obj == null) continue;
                byte type = ref.fObjType.getByte(obj);
                if (type == 2) {
                    int id = ref.fNpcId.getInt(obj);
                    String npcName = (String) ref.fObjName.get(obj);
                    String normNpc = normalizeText(ref, npcName);
                    if (id == -10 || normNpc.contains("dadichchuyen") || normNpc.contains("dichchuyen")) {
                        if (mapsWithTeleportStone.add(currentMapId)) knowledgeDirty = true;
                        lockedTeleportMaps.remove(currentMapId);
                    }
                    if (normNpc.contains("missanna") || normNpc.equals("anna") || normNpc.contains("tinhtu")) {
                        ignoredNpcIds.add(id);
                    } else if (isTownMainNpc(id)) {
                        knownNpcMap.put(id, 1);
                    } else if (id != -10 && id != -32 && id != -7 && !normNpc.startsWith("khu") && !normNpc.contains("linhcanh")) {
                        // Chi luu vi tri cho NPC duy nhat, khong ghi de NPC xuat hien o nhieu Map theo cot truyen
                        if (!knownNpcMap.containsKey(id)) {
                            knownNpcMap.put(id, currentMapId);
                            knowledgeDirty = true;
                        }
                    }
                    if (seenNpcs.add(id)) {
                        npcSummary.add((npcName != null ? npcName : "NPC") + "(ID=" + id + ")");
                    }
                } else if ((ref.clsMonsterBase != null && ref.clsMonsterBase.isInstance(obj)) || type == 1) {
                    if (ref.clsMonsterBase != null && ref.clsMonsterBase.isInstance(obj)) {
                        int mobId = ref.fMobTemplateId.getInt(obj);
                        if (mobId == 0 && currentMapId > 0) continue;

                        mobCountOnMap++;
                        if (currentMapId != 1) {
                            Integer prev = knownMobMap.putIfAbsent(mobId, currentMapId);
                            if (prev == null || prev != currentMapId) knowledgeDirty = true;
                        }
                        String liveMobName = (String) ref.fObjName.get(obj);
                        if (liveMobName != null && !liveMobName.trim().isEmpty()) {
                            knownMobNameMap.put(mobId, liveMobName.trim());
                        }
                        int tplLv = getMonsterTemplateLevel(ref, mobId);
                        if (tplLv > 0) {
                            Integer prevLv = knownMobLevelMap.put(mobId, tplLv);
                            if (prevLv == null || prevLv != tplLv) knowledgeDirty = true;
                            if (currentMapId > 1) {
                                mapObservedMaxMobLv.merge(currentMapId, tplLv, Math::max);
                                mapObservedMinMobLv.merge(currentMapId, tplLv, Math::min);
                            }
                        }
                        if (seenMobs.add(mobId)) {
                            String mName = (liveMobName != null && !liveMobName.isEmpty())
                                    ? liveMobName : getMonsterTemplateName(ref, mobId);
                            if (mName != null && !mName.isEmpty()) {
                                knownMobNameMap.put(mobId, mName.trim());
                            }
                            mobSummary.add(mName + "(ID=" + mobId + (tplLv > 0 ? ",Lv=" + tplLv : "") + ")");
                        }
                    }
                }
            }

            if (lastLoggedMapNpcs != currentMapId && !npcSummary.isEmpty()) {
                lastLoggedMapNpcs = currentMapId;
                logRateLimited("MAP_NPCS_" + currentMapId, 5000L,
                        "[AUTO-QUEST] NPC t\u1ea1i Map " + currentMapId + ": " + String.join(", ", npcSummary));
            }
            if (lastLoggedMapMobs != currentMapId && !mobSummary.isEmpty()) {
                lastLoggedMapMobs = currentMapId;
                logRateLimited("MAP_MOBS_" + currentMapId, 5000L,
                        "[AUTO-QUEST] Qu\u00e1i t\u1ea1i Map " + currentMapId + ": " + String.join(", ", mobSummary));
            }
        } catch (Throwable ignored) {}
        return mobCountOnMap;
    } 

    private static int[] getCandidateMapsForNpc(int npcId, String normQuestAllText) {
        if (isTownMainNpc(npcId)) return new int[]{1};
        if (npcId == -1) return new int[]{0, 1};
        if (npcId == -35 || npcId == 35 || npcId == -34 || npcId == 34) return new int[]{7, 18, 1};
        if (npcId == -59 || npcId == 59) return new int[]{17, 16, 15, 1};
        if (npcId == -36 || npcId == -37 || npcId == -69 || npcId == -81) return new int[]{1};
        if (npcId == -9 || npcId == 9) return new int[]{12, 11, 13, 14, 2, 19, 1};
        if (npcId == -54 || npcId == 54) return new int[]{19, 33, 67, 1};
        if (npcId == -71 || npcId == 71) return new int[]{67, 73, 19, 33};

        if (normQuestAllText.contains("phacuahang") || normQuestAllText.contains("hangsoiquy")
                || normQuestAllText.contains("canhdongsoi") || normQuestAllText.contains("cuopbien")) {
            return new int[]{7, 18, 1};
        }
        if (normQuestAllText.contains("hangdoi") || normQuestAllText.contains("mrfrank") || normQuestAllText.contains("frank")) {
            return new int[]{17, 16, 1};
        }
        if (normQuestAllText.contains("thuythu") || normQuestAllText.contains("gapnan") || normQuestAllText.contains("ngoaikhoi")) {
            return new int[]{12, 11, 13, 14, 19, 1};
        }
        if (normQuestAllText.contains("truonglang") || normQuestAllText.contains("ngoilangnho")) return new int[]{0, 1};
        if (normQuestAllText.contains("khumo") || normQuestAllText.contains("thomo")) return new int[]{2, 1, 0};
        if (normQuestAllText.contains("cuabien") || normQuestAllText.contains("haku")) return new int[]{19, 33, 12, 1};
        return new int[]{1, 7, 12, 11, 2, 17, 19, 33, 0, 5, 9, 13, 16, 25, 41, 45, 67, 73, 92};
    }

    private static int getKnownStoryBossOrMetaphorMap(RefCache ref, int targetId, String targetMobName, String normAllText) {
        String normMob = normalizeText(ref, targetMobName);

        if (targetId == 5 || normMob.contains("cayquaivat") || normMob.contains("\u6811\u602a")) return 6;
        if (targetId == 11 || normMob.contains("ongkhonglo") || normMob.contains("ongchua")) return 10;
        if (normMob.contains("bachtuoc") && !normMob.contains("gai")) return 14;
        if (normMob.contains("thuyentruong") || normMob.contains("cuopbien") || normMob.contains("kraken")) return 14;
        if (normMob.contains("doichua") || normMob.contains("macarong") || normMob.contains("doihut") || normMob.contains("dracula")) return 17;
        if (normMob.contains("soiquy") || normMob.contains("soidaudan") || normMob.contains("mausoi")) return 18;
        if (normMob.contains("guardian") || normMob.contains("hoveda") || normMob.contains("nguoida") || normMob.contains("khongloda")) return 28;
        if (normMob.contains("mummy") || normMob.contains("vuaxacuop") || normMob.contains("pharaoh") || normMob.contains("xacuopcodai")) return 32;
        if (normMob.contains("tuongxuong") || normMob.contains("chuaquy") || normMob.contains("tuthan")) return 47;
        if (normMob.contains("thienthan") || normMob.contains("archangel")) return 51;
        if (normMob.contains("cerberus") || normMob.contains("balrog") || normMob.contains("quyvuong") || normMob.contains("dianguc")) return 62;
        if (normMob.contains("medusa")) return 63;
        if (normMob.contains("chimera")) return 64;
        if (normMob.contains("minotaur") || normMob.contains("chuamecung") || normMob.contains("bossmecung")) return 79;
        if (normMob.contains("yeti") || normMob.contains("nguoituyetkhonglo") || normMob.contains("rongbang") || normMob.contains("chuatuyet")) return 98;

        if (normAllText.contains("tangbiada") || normAllText.contains("tangdaghinho") || normAllText.contains("baidattrong")) return 10;
        if (normAllText.contains("hothuyquai") || normAllText.contains("quanhboho") || normAllText.contains("senxanh")) {
            return normAllText.contains("biada") ? 10 : 9;
        }
        if (normAllText.contains("khevuc") || (normAllText.contains("rungaogiac") && normAllText.contains("sinhvathian"))) return 6;
        if (normAllText.contains("nghiadiataudam") || normAllText.contains("xactau") || normAllText.contains("contaudam")) return 14;
        if (normAllText.contains("hangdoi")) return 17;
        if (normAllText.contains("hangsoiquy")) return 18;
        if (normAllText.contains("bossguardian")) return 28;
        if (normAllText.contains("hammoquaivat") || normAllText.contains("tangsaunhat")) return 32;
        if (normAllText.contains("doixacchet")) return 47;
        if (normAllText.contains("khuvuon")) return 50;
        if (normAllText.contains("congthiendang")) return 51;
        if (normAllText.contains("dianguctang1")) return 62;
        if (normAllText.contains("mecungtangcuoi")) return 79;
        if (normAllText.contains("tramnuituyet")) return 98;

        return -1;
    }

    private static int getBranchBossRoom(int mapInBranch) {
        switch (mapInBranch) {
            case 3: case 4: case 5: case 6: return 6;
            case 7: case 8: case 9: case 10: return 10;
            case 11: case 12: case 13: case 14: return 14;
            case 15: case 16: case 17: return 17;
            case 25: case 26: case 27: case 28: return 28;
            case 29: case 30: case 31: case 32: return 32;
            case 37: case 38: case 39: case 40: case 41: case 51: return 51;
            case 42: case 43: case 44: case 45: case 52: case 62: return 62;
            case 74: case 75: case 76: case 77: case 78: case 79: return 79;
            case 94: case 95: case 96: case 97: case 98: return 98;
            default: return -1;
        }
    }

    private static int selectNextMapInStoryBranch(int anchorMap, boolean isBossQuest) {
        if (isBossQuest) {
            int bossRoom = getBranchBossRoom(anchorMap);
            if (bossRoom >= 0 && !searchedEmptyMaps.contains(bossRoom)) return bossRoom;
        }
        if (anchorMap >= 0 && !searchedEmptyMaps.contains(anchorMap)) return anchorMap;

        for (int[] branch : STORY_BRANCHES) {
            int pos = -1;
            for (int i = 0; i < branch.length; i++) {
                if (branch[i] == anchorMap) {
                    pos = i;
                    break;
                }
            }
            if (pos >= 0) {
                for (int i = pos + 1; i < branch.length; i++) {
                    if (!searchedEmptyMaps.contains(branch[i])) return branch[i];
                }
                for (int i = pos - 1; i >= 0; i--) {
                    if (!searchedEmptyMaps.contains(branch[i])) return branch[i];
                }
            }
        }

        int startIdx = 0;
        for (int i = 0; i < HUNTING_MAP_ORDER.length; i++) {
            if (HUNTING_MAP_ORDER[i] == anchorMap) {
                startIdx = i;
                break;
            }
        }
        for (int i = startIdx; i < HUNTING_MAP_ORDER.length; i++) {
            int m = HUNTING_MAP_ORDER[i];
            if (!searchedEmptyMaps.contains(m)) return m;
        }

        searchedEmptyMaps.clear();
        return anchorMap >= 0 ? anchorMap : 3;
    }

    private static void notifyNavigatorMapLoaded(RefCache ref, int currentMapId, int prevMapId) {
        if (ref == null || ref.mGetNav == null || ref.ctorMapHolder == null
                || ref.mSetMapHolderId == null || ref.mNavOnMapChanged == null) return;
        try {
            Object navInstance = invokeMethod(ref.mGetNav, null);
            Object mapHolder = invokeCtor(ref.ctorMapHolder);
            invokeMethod(ref.mSetMapHolderId, mapHolder, currentMapId);
            invokeMethod(ref.mNavOnMapChanged, navInstance, mapHolder, prevMapId);
        } catch (Throwable ignored) {}
    }

    private static void syncMapChangeWithNavigator(RefCache ref, int currentMapId) {
        if (currentMapId < 0) return;
        try {
            if (mapsWithTeleportStone.contains(currentMapId)) {
                lockedTeleportMaps.remove(currentMapId);
            }

            if (lastRecordedMapId < 0) {
                long now = System.currentTimeMillis();
                lastRecordedMapId = currentMapId;
                lastPreviousMapId = currentMapId;
                currentMapEnterTime = now;
                lastTargetSeenOrQuestChangeTime = now;
                currentStoneAttemptStartTime = now;
                lastPositionChangeTime = now;
                lastPatrolIndex = 0;
                lockedTargetMob = null;
                approachTrackedMob = null;
                temporarilyIgnoredMobs.clear();
                mapVisitCountsInTrip.put(currentMapId, 1);
                notifyNavigatorMapLoaded(ref, currentMapId, currentMapId);
                return;
            }

            if (currentMapId != lastRecordedMapId) {
                long now = System.currentTimeMillis();
                int prevMapId = lastRecordedMapId;
                lastPreviousMapId = prevMapId;
                lastRecordedMapId = currentMapId;
                currentMapEnterTime = now;
                lastTargetSeenOrQuestChangeTime = now;
                currentStoneAttemptStartTime = now;
                lastPositionChangeTime = now;
                lastPatrolIndex = 0;
                lastRouteTime = 0;
                currentRoutingTargetMap = -1;
                currentRoutingTargetX = -1;
                currentRoutingTargetY = -1;
                lastRoutedFromMapId = -1;
                lockedTargetMob = null;
                approachTrackedMob = null;
                temporarilyIgnoredMobs.clear();
                consecutiveStuckRecoveries = 0;
                spotCrowdedSinceTime = 0L;
                visitedCrowdedZones.clear();
                releaseAllDirectionalKeys();

                int visits = mapVisitCountsInTrip.getOrDefault(currentMapId, 0) + 1;
                mapVisitCountsInTrip.put(currentMapId, visits);

                // Chong lap vong giua 2 map qua 2 lan
                if (visits > 2 && currentMapId != currentTripGoalMap) {
                    useTeleportForCurrentTrip = false;
                    logRateLimited("MAP_LOOP_GUARD", 5000L,
                            "[AUTO-QUEST] Ph\u00e1t hi\u1ec7n l\u1eb7p Map " + currentMapId + " (" + visits
                                    + " l\u1ea7n trong l\u1ed9 tr\u00ecnh t\u1edbi Map " + currentTripGoalMap + ") -> T\u1eaft d\u1ecbch chuy\u1ec3n \u0111\u1ec3 \u0111i b\u1ed9 tr\u1ef1c ti\u1ebfp.");
                } else if (isDirectlyConnectedWalkMap(prevMapId, currentMapId) && currentMapId != currentTripGoalMap) {
                    useTeleportForCurrentTrip = false;
                }

                notifyNavigatorMapLoaded(ref, currentMapId, prevMapId);
                System.err.println("[AUTO-QUEST] \u0110\u00e3 chuy\u1ec3n Map: " + prevMapId + " -> " + currentMapId
                        + " (\u0110\u00edch hi\u1ec7n t\u1ea1i: Map " + currentTripGoalMap + ")");
            }
        } catch (Throwable ignored) {
        } finally {
            lastRecordedMapId = currentMapId;
        }
    }

    private static String stripViDiacriticsCompact(String input) {
        if (input == null || input.isEmpty()) return "";
        String nfd = Normalizer.normalize(input, Normalizer.Form.NFD);
        String noMarks = nfd.replaceAll("\\p{InCombiningDiacriticalMarks}+", "")
                .replace('\u0111', 'd').replace('\u0110', 'd');
        return noMarks.toLowerCase().replaceAll("[^a-z0-9]+", "");
    }

    private static String normalizeText(RefCache ref, String input) {
        if (input == null || input.isEmpty()) return "";
        try {
            if (ref != null && ref.mNormText != null) {
                String res = (String) invokeMethod(ref.mNormText, null, input);
                if (res != null && !res.isEmpty()) {
                    return res.replaceAll("\\s+", "");
                }
            }
        } catch (Throwable ignored) {}
        return stripViDiacriticsCompact(input);
    }

    private static String extractAllTextFromQuest(RefCache ref, Object quest) {
        if (quest == null || ref == null || ref.clsQuest == null) return "";
        StringBuilder sb = new StringBuilder();
        try {
            for (Field f : ref.clsQuest.getDeclaredFields()) {
                if (Modifier.isStatic(f.getModifiers())) continue;
                f.setAccessible(true);
                Object val = f.get(quest);
                if (val == null) continue;
                if (val instanceof String) {
                    sb.append((String) val).append(" ");
                } else if (val instanceof String[]) {
                    for (String s : (String[]) val) {
                        if (s != null) sb.append(s).append(" ");
                    }
                } else if (ref.clsVector != null && ref.clsVector.isInstance(val)) {
                    Method[] vm = getVectorMethods(val);
                    if (vm != null) {
                        int sz = Math.min(20, (Integer) invokeMethod(vm[0], val));
                        for (int i = 0; i < sz; i++) {
                            Object item = invokeMethod(vm[1], val, i);
                            if (item instanceof String) sb.append((String) item).append(" ");
                        }
                    }
                }
            }
        } catch (Throwable ignored) {}
        return sb.toString();
    }

    private static int matchLastDestinationMapFromText(RefCache ref, String rawText) {
        if (rawText == null || rawText.isEmpty() || ref == null || ref.fMapNames == null) return -1;
        try {
            String normText = normalizeText(ref, rawText);
            if (normText.isEmpty()) return -1;

            String[] mapNames = (String[]) ref.fMapNames.get(null);
            int bestMapId = -1;
            int bestEndPos = -1;
            int bestLen = 0;

            if (mapNames != null) {
                for (int mId = 0; mId < mapNames.length; mId++) {
                    if (!VALID_WORLD_MAPS.contains(mId)) continue;
                    String rawMapName = mapNames[mId];
                    if (rawMapName == null || rawMapName.isEmpty()) continue;
                    String normMapName = normalizeText(ref, rawMapName);
                    if (normMapName.length() < 4 || normMapName.startsWith("map")) continue;

                    int idx = normText.lastIndexOf(normMapName);
                    if (idx >= 0) {
                        int endPos = idx + normMapName.length();
                        if (endPos > bestEndPos || (endPos == bestEndPos && normMapName.length() > bestLen)) {
                            bestEndPos = endPos;
                            bestLen = normMapName.length();
                            bestMapId = mId;
                        }
                    }
                }
            }
            return bestMapId;
        } catch (Throwable ignored) {}
        return -1;
    }

    private static int getMonsterTemplateLevel(RefCache ref, int mobTemplateId) {
        if (mobTemplateId < 0) return -1;
        if (ref != null && ref.mGetMobTpl != null) {
            try {
                Object mobTpl = invokeMethod(ref.mGetMobTpl, null, mobTemplateId);
                if (mobTpl != null) {
                    if (cachedMobTplLevelField == null) {
                        cachedMobTplLevelField = mobTpl.getClass().getDeclaredField("n0002");
                        cachedMobTplLevelField.setAccessible(true);
                    }
                    Object val = cachedMobTplLevelField.get(mobTpl);
                    if (val instanceof Number) {
                        int lv = ((Number) val).intValue();
                        if (lv > 0) {
                            knownMobLevelMap.put(mobTemplateId, lv);
                            return lv;
                        }
                    }
                }
            } catch (Throwable ignored) {}
        }
        Integer cachedLv = knownMobLevelMap.get(mobTemplateId);
        return cachedLv != null ? cachedLv : -1;
    }

    /**
     * Tra cuu cap do quai toi thieu cua mot Map dua tren DEFAULT_LEVEL_SPOTS (n0211)
     * va cac phong Boss cuoi nhanh (khi server chua gui template cua quai o map xa).
     */
    private static int getMinMobLevelForMap(RefCache ref, int mapId) {
        if (mapId <= 1) return 1;
        if (ref != null && ref.fDefaultSpots != null) {
            try {
                int[][] spots = (int[][]) ref.fDefaultSpots.get(null);
                if (spots != null) {
                    int minLv = Integer.MAX_VALUE;
                    for (int[] s : spots) {
                        if (s != null && s.length >= 6 && s[2] == mapId && s[0] > 0) {
                            if (s[0] < minLv) minLv = s[0];
                        }
                    }
                    if (minLv != Integer.MAX_VALUE) return minLv;
                }
            } catch (Throwable ignored) {}
        }
        switch (mapId) {
            case 2: return 2;
            case 6: return 10;
            case 10: return 20;
            case 14: return 30;
            case 17: return 39;
            case 18: return 40;
            case 28: return 50;
            case 32: return 60;
            case 33: case 34: case 35: return 51;
            case 37: return 72;
            case 38: return 76;
            case 39: return 80;
            case 40: return 85;
            case 47: case 50: return 70;
            case 52: case 62: return 90;
            case 66: return 100;
            case 67: case 68: case 69: case 70: return 95;
            case 92: case 93: return 125;
            default: return -1;
        }
    }

    /**
     * Xac dinh cap do cua quai muc tieu trong nhiem vu.
     * Ket hop ca Template cua quai (n0088.n0003) va cap do Map muc tieu (DEFAULT_LEVEL_SPOTS).
     */
    private static int resolveQuestMobLevel(RefCache ref, int effectiveMobId, int questTargetMapId, boolean isBossQuest) {
        int tplLv = getMonsterTemplateLevel(ref, effectiveMobId);
        int mapLv = getMinMobLevelForMap(ref, questTargetMapId);
        if (tplLv > 0 && mapLv > 0) {
            return isBossQuest ? Math.max(tplLv, mapLv) : tplLv;
        }
        return (tplLv > 0) ? tplLv : mapLv;
    }

    /**
     * Tinh cap do nhan vat toi thieu can dat truoc khi bat dau di lam nhiem vu.
     * Quy tac: Nhan vat phai dat it nhat (mobLevel - 1), tuc la chi duoi quai can danh toi da 1 Lv
     * thi moi bat dau di lam nhiem vu; neu thap hon thi bat cay quai theo Lv.
     */
    private static int getRequiredPlayerLevelForQuest(
            RefCache ref, int qId, int effectiveMobId, int questTargetMapId, boolean isBossQuest) {
        int mobLv = resolveQuestMobLevel(ref, effectiveMobId, questTargetMapId, isBossQuest);
        int obsLv = observedQuestMobLevel.getOrDefault(qId, -1);
        if (obsLv > mobLv) {
            mobLv = obsLv;
        }
        int reqLv = (mobLv > 1) ? (mobLv - MAX_ALLOWED_MOB_LEVEL_GAP) : 1;

        // Dam bao du Level de buoc qua cong Map dac biet (Map 33: Lv.40, Map 67: Lv.80, Map 92: Lv.100)
        int reqMapLv = getMinLevelForSpecialMap(questTargetMapId);
        if (reqMapLv > reqLv) {
            reqLv = reqMapLv;
        }

        Integer diedLv = questLevelWhenDied.get(qId);
        if (diedLv != null && diedLv > 0) {
            int deaths = questDeathCounts.getOrDefault(qId, 1);
            int deathReqLv = diedLv + Math.min(5, deaths);
            reqLv = Math.max(reqLv, deathReqLv);
        }
        return reqLv;
    }

    /**
     * Kiem tra nhanh mot nhiem vu dang nhan (active quest) da du dieu kien Level
     * (duoi quai can danh toi da 1 Lv) de di lam ngay hay chua, hay can di cay cap truoc.
     */
    private static boolean isActiveQuestReadyForPlayerLevel(RefCache ref, Object quest, int playerLevel, int currentMapId) {
        if (quest == null || ref == null) return false;
        try {
            int qId = ref.fQId.getInt(quest);
            int qType = ((Number) ref.fQType.get(quest)).intValue();
            int npcId = ref.fQNpcId.getInt(quest);

            if (qType == 2 || qType == 3) {
                int npcMapId = resolveNpcReportMapId(ref, quest, npcId, qId, currentMapId);
                int reqMapLv = getMinLevelForSpecialMap(npcMapId);
                return reqMapLv <= 0 || playerLevel >= reqMapLv;
            }

            Object targetsObj = ref.fQTargets.get(quest);
            Object maxCountsObj = ref.fQMaxCounts.get(quest);
            Object curCountsObj = ref.fQCurCounts.get(quest);

            int targetId = -1;
            int maxProgress = 0;
            boolean hasTargetArray = false;
            boolean allDone = false;

            if (targetsObj != null && maxCountsObj != null && curCountsObj != null) {
                int len = Math.min(Array.getLength(targetsObj),
                        Math.min(Array.getLength(maxCountsObj), Array.getLength(curCountsObj)));
                if (len > 0) {
                    hasTargetArray = true;
                    allDone = true;
                    for (int i = 0; i < len; i++) {
                        int cur = ((Number) Array.get(curCountsObj, i)).intValue();
                        int max = ((Number) Array.get(maxCountsObj, i)).intValue();
                        int tId = ((Number) Array.get(targetsObj, i)).intValue();
                        if (max <= 0 || cur < max) {
                            targetId = tId;
                            maxProgress = max;
                            allDone = false;
                            break;
                        }
                    }
                }
            }
            if (hasTargetArray && allDone) {
                int npcMapId = resolveNpcReportMapId(ref, quest, npcId, qId, currentMapId);
                int reqMapLv = getMinLevelForSpecialMap(npcMapId);
                return reqMapLv <= 0 || playerLevel >= reqMapLv;
            }

            String combinedQuestText = extractAllTextFromQuest(ref, quest);
            int effectiveMobId = (qType == 1 && targetId >= 0)
                    ? targetId : inferMonsterIdFromQuestText(ref, quest, combinedQuestText);

            boolean isBossQuest = (qType == 1 && maxProgress == 1);
            int questTargetMapId = resolveQuestTargetMapId(ref, quest, effectiveMobId, maxProgress, currentMapId);
            int reqMapLv = getMinLevelForSpecialMap(questTargetMapId);
            int reqLv = getRequiredPlayerLevelForQuest(ref, qId, effectiveMobId, questTargetMapId, isBossQuest);
            if (reqMapLv > reqLv) reqLv = reqMapLv;

            return playerLevel >= reqLv;
        } catch (Throwable ignored) {
            return true;
        }
    }

    private static String getMonsterTemplateName(RefCache ref, int mobTemplateId) {
        if (mobTemplateId < 0) return "";
        String cachedName = knownMobNameMap.get(mobTemplateId);
        if (ref != null && ref.mGetMobTpl != null) {
            try {
                Object mobTpl = invokeMethod(ref.mGetMobTpl, null, mobTemplateId);
                if (mobTpl != null) {
                    if (cachedMobTplNameField == null) {
                        cachedMobTplNameField = mobTpl.getClass().getDeclaredField("n0005");
                        cachedMobTplNameField.setAccessible(true);
                    }
                    String name = (String) cachedMobTplNameField.get(mobTpl);
                    if (name != null && !name.trim().isEmpty()) {
                        knownMobNameMap.put(mobTemplateId, name.trim());
                        return name.trim();
                    }
                }
            } catch (Throwable ignored) {}
        }
        return cachedName != null ? cachedName : "";
    }

    private static int[] getFarmSpotForMap(RefCache ref, int mapId) {
        if (ref == null || ref.fDefaultSpots == null) return null;
        try {
            int[][] spots = (int[][]) ref.fDefaultSpots.get(null);
            if (spots != null) {
                for (int[] s : spots) {
                    if (s != null && s.length >= 6 && s[2] == mapId && s[4] > 0 && s[5] > 0) {
                        return new int[]{s[4], s[5]};
                    }
                }
            }
        } catch (Throwable ignored) {}
        return null;
    }

    /**
     * Xep hang do uu tien cua cap do quai (mobLv) so voi cap do nhan vat (playerLevel):
     * - Chi nhan quai trong khoang [playerLevel - 2, playerLevel + 2] (rieng Lv 1..3 cho phep quai tu Lv 1).
     * - Neu quai <= playerLevel - 3 (tuc -3 Lv tro xuong): tra ve -1 de buoc chuyen sang danh quai manh hon.
     * - Neu quai > playerLevel + 2: tra ve -1 (qua manh).
     * - Thu tu uu tien tra ve (0 la uu tien cao nhat):
     *   0: -2 Lv (Uu tien so 1)
     *   1: -1 Lv (Uu tien so 2)
     *   2:  0 Lv (Bang Lv nhan vat)    
     *   3: +1 Lv
     *   4: +2 Lv
     */
    private static int getMobLevelPriorityRank(int playerLevel, int mobLv) {
        if (mobLv <= 0) return -1;
        int diff = mobLv - playerLevel;
        if (playerLevel >= 4 && diff <= -3) {
            return -1; // Sửa từ -0 thành -1
        }
        if (diff < -2) {
            return (playerLevel <= 3 && mobLv >= 1) ? 1 : -1;
        }
        if (diff > 2) {
            return -1;
        }
        for (int r = 0; r < LEVEL_DIFF_PRIORITY_ORDER.length; r++) {
            if (LEVEL_DIFF_PRIORITY_ORDER[r] == diff) {
                return r;
            }
        }
        return -1;
    }

    /**
     * Quet Level hien tai cua nhan vat, tim bai quai trong khoang +-2 Lv so voi nhan vat,
     * uu tien -2 Lv (-2 -> -1 -> 0 -> +1 -> +2). Neu bai hien tai chi co quai <= -3 Lv
     * thi tu dong tien len bai co quai manh hon.
     * Tra ve mang 4 phan tu: {mapId, spotX, spotY, targetMobLv}
     */
    
    // --- NEO CỐ ĐỊNH BÃI QUÁI ĐÔNG NHẤT (DÙNG CHO CẢ LÀM NHIỆM VỤ & CÀY LEVEL) ---
    private static final int FARM_SPOT_RADIUS = 185;
    private static int lockedSpotMapId = -1;
    private static int lockedSpotTargetKey = -99999;
    private static int lockedSpotX = -1;
    private static int lockedSpotY = -1;
    private static int lockedSpotMobCount = 0;

    private static void resetLockedMonsterSpot() {
        lockedSpotMapId = -1;
        lockedSpotTargetKey = -99999;
        lockedSpotX = -1;
        lockedSpotY = -1;
        lockedSpotMobCount = 0;
    }

    /**
     * Tìm và khóa cố định tâm bãi quái (spotX, spotY) có nhiều quái mục tiêu tụ tập nhất trên bản đồ.
     * Tính cả quái đang sống và quái vừa chết đang chờ hồi sinh để tâm bãi không bao giờ bị lệch khi đang đánh.
     * Trả về mảng: {spotX, spotY, clusterCount}
     */
    private static int[] resolveBestClusterSpotOnMap(
            RefCache ref, int currentMapId, int targetKey, List<int[]> allTargetMobCoords) {
        // 1. Nếu đã khóa cứng tâm bãi cho Map và mục tiêu này rồi -> Giữ cố định 100%, không tính lại!
        if (lockedSpotMapId == currentMapId && lockedSpotTargetKey == targetKey
                && lockedSpotX > 0 && lockedSpotY > 0) {
            int countNearSpot = (allTargetMobCoords != null) ? allTargetMobCoords.size() : lockedSpotMobCount;
            return new int[]{lockedSpotX, lockedSpotY, countNearSpot};
        }

        // 2. Ưu tiên lấy tọa độ bãi chuẩn từ bảng DEFAULT_LEVEL_SPOTS của Mod (n0211)
        if (ref != null && ref.fDefaultSpots != null) {
            try {
                int[][] spots = (int[][]) ref.fDefaultSpots.get(null);
                if (spots != null) {
                    int targetMobLv = (targetKey > 0) ? getMonsterTemplateLevel(ref, targetKey) : lastKnownPlayerLevel;
                    int[] matchedModSpot = null;
                    int minLvDiff = Integer.MAX_VALUE;

                    for (int[] s : spots) {
                        if (s == null || s.length < 6 || s[2] != currentMapId || s[4] <= 0 || s[5] <= 0) continue;
                        if (targetMobLv > 0) {
                            int midLv = (s[0] + s[1]) / 2;
                            int diff = Math.abs(targetMobLv - midLv);
                            if (diff < minLvDiff) {
                                minLvDiff = diff;
                                matchedModSpot = s;
                            }
                        } else {
                            matchedModSpot = s;
                            break;
                        }
                    }

                    if (matchedModSpot != null) {
                        // Kiểm tra nếu trong danh sách quái hiện tại có quái nằm gần bãi của Mod (hoặc nhân vật chưa chạy tới bãi Mod)
                        lockedSpotMapId = currentMapId;
                        lockedSpotTargetKey = targetKey;
                        lockedSpotX = matchedModSpot[4];
                        lockedSpotY = matchedModSpot[5];
                        lockedSpotMobCount = (allTargetMobCoords != null) ? allTargetMobCoords.size() : 1;
                        System.err.println("[AUTO-SPOT] Kh\u00f3a c\u1ed1 \u0111\u1ecbnh b\u00e3i chu\u1ea9n Mod (n0211) t\u1ea1i Map "
                                + currentMapId + ": T\u00e2m=(" + lockedSpotX + "," + lockedSpotY + ")");
                        return new int[]{lockedSpotX, lockedSpotY, lockedSpotMobCount};
                    }
                }
            } catch (Throwable ignored) {}
        }

        // 3. Nếu Map không có trong DEFAULT_LEVEL_SPOTS -> Tính tâm cụm đông nhất 1 lần duy nhất rồi khóa cứng
        if (allTargetMobCoords == null || allTargetMobCoords.isEmpty()) {
            return null;
        }

        int bestCx = allTargetMobCoords.get(0)[0];
        int bestCy = allTargetMobCoords.get(0)[1];
        int bestCount = 0;

        for (int[] cand : allTargetMobCoords) {
            int count = 0;
            long sumX = 0;
            long sumY = 0;
            for (int[] m : allTargetMobCoords) {
                if (Math.hypot(m[0] - cand[0], m[1] - cand[1]) <= FARM_SPOT_RADIUS) {
                    count++;
                    sumX += m[0];
                    sumY += m[1];
                }
            }
            if (count > bestCount) {
                bestCount = count;
                bestCx = (int) (sumX / count);
                bestCy = (int) (sumY / count);
            }
        }

        lockedSpotMapId = currentMapId;
        lockedSpotTargetKey = targetKey;
        lockedSpotX = bestCx;
        lockedSpotY = bestCy;
        lockedSpotMobCount = bestCount;

        System.err.println("[AUTO-SPOT] Kh\u00f3a c\u1ed1 \u0111\u1ecbnh t\u00e2m b\u00e3i qu\u00e1i t\u1ea1i Map " + currentMapId
                + ": T\u00e2m=(" + lockedSpotX + "," + lockedSpotY + ") | S\u1ed1 qu\u00e1i=" + lockedSpotMobCount);
        return new int[]{lockedSpotX, lockedSpotY, lockedSpotMobCount};
    }

    private static int[] getFarmMapAndCoordsForLevel(RefCache ref, int playerLevel) {
        int safeLv = Math.max(1, playerLevel);

        try {
            // Ưu tiên 1: Gọi hàm chọn bãi theo Level trong Mod (n0211.n0001_selectSpotForLevel)
            // nhưng chỉ nhận nếu Map đó không vượt quá cổng giới hạn Level hiện tại của nhân vật
            if (ref != null && ref.mSelectSpotForLv != null) {
                int[] modSpot = (int[]) invokeMethod(ref.mSelectSpotForLv, null, safeLv);
                if (modSpot != null && modSpot.length >= 6 && modSpot[2] > 1) {
                    int mapId = modSpot[2];
                    int reqMapLv = getMinLevelForSpecialMap(mapId);
                    if (reqMapLv <= 0 || safeLv >= reqMapLv) {
                        int spotX = (modSpot[4] > 0) ? modSpot[4] : 600;
                        int spotY = (modSpot[5] > 0) ? modSpot[5] : 864;
                        int targetLv = (modSpot[1] > 0) ? modSpot[1] : safeLv;
                        currentSelectedFarmMobLv = targetLv;
                        return new int[]{mapId, spotX, spotY, targetLv};
                    }
                }
            }

            // Ưu tiên 2: Tra bảng DEFAULT_LEVEL_SPOTS của n0211 (bỏ qua các Map nhân vật chưa đủ Lv qua cổng)
            int[][] spots = (ref != null && ref.fDefaultSpots != null)
                    ? (int[][]) ref.fDefaultSpots.get(null) : null;

            if (spots != null) {
                int[] bestSpot = null;
                int minGap = Integer.MAX_VALUE;

                for (int[] s : spots) {
                    if (s == null || s.length < 6 || s[2] <= 1) continue;
                    int reqMapLv = getMinLevelForSpecialMap(s[2]);
                    if (reqMapLv > 0 && safeLv < reqMapLv) continue; // Chặn map yêu cầu Lv.40 / Lv.80 khi chưa đủ cấp

                    int minLv = s[0];
                    int maxLv = (s[1] >= s[0]) ? s[1] : s[0];

                    if (safeLv >= minLv && safeLv <= maxLv) {
                        bestSpot = s;
                        break;
                    }

                    int gap = (safeLv < minLv) ? (minLv - safeLv) : (safeLv - maxLv);
                    if (gap < minGap) {
                        minGap = gap;
                        bestSpot = s;
                    }
                }

                if (bestSpot != null) {
                    int mapId = bestSpot[2];
                    int spotX = (bestSpot[4] > 0) ? bestSpot[4] : 600;
                    int spotY = (bestSpot[5] > 0) ? bestSpot[5] : 864;
                    int targetLv = (bestSpot[1] > 0) ? bestSpot[1] : safeLv;
                    currentSelectedFarmMobLv = targetLv;
                    return new int[]{mapId, spotX, spotY, targetLv};
                }
            }
        } catch (Throwable ignored) {}

        currentSelectedFarmMobLv = safeLv;
        return new int[]{3, 600, 864, safeLv};
    }

    private static int getFarmMapForLevel(RefCache ref, int level) {
        return getFarmMapAndCoordsForLevel(ref, level)[0];
    }
    
    

    private static int inferMonsterIdFromQuestText(RefCache ref, Object quest, String rawQuestText) {
        if (ref == null) return -1;
        try {
            List<String> textSegments = new ArrayList<>();
            if (quest != null) {
                try {
                    String sShort = (String) ref.fQShort.get(quest);
                    String sHelp = (String) ref.fQHelp.get(quest);
                    String sName = (String) ref.fQName.get(quest);
                    if (sShort != null && !sShort.isEmpty()) textSegments.add(sShort);
                    if (sHelp != null && !sHelp.isEmpty()) textSegments.add(sHelp);
                    if (sName != null && !sName.isEmpty()) textSegments.add(sName);
                } catch (Throwable ignored) {}
            }
            if (rawQuestText != null && !rawQuestText.isEmpty()) {
                textSegments.add(rawQuestText);
            }
            if (textSegments.isEmpty()) return -1;

            int currentMapId = getCurrentMapId(ref);

            // Buoc 1: Quet truc tiep cac quai dang co mat tren ban do hien tai (fVecActors)
            // Giup nhan dien ngay quai nhiem vu (nhu Cao xanh ID=14 o Map 12) ke ca khi n0088.n0003 chua nap kip
            Object vecActors = (ref.fVecActors != null) ? ref.fVecActors.get(null) : null;
            if (vecActors != null) {
                Method[] vm = getVectorMethods(vecActors);
                if (vm != null) {
                    int count = (Integer) invokeMethod(vm[0], vecActors);
                    for (String seg : textSegments) {
                        String normSeg = normalizeText(ref, seg);
                        if (normSeg.isEmpty()) continue;
                        int bestLiveId = -1;
                        int bestLiveLen = 0;
                        for (int i = 0; i < count; i++) {
                            Object obj = invokeMethod(vm[1], vecActors, i);
                            if (obj == null || !ref.clsMonsterBase.isInstance(obj)) continue;
                            int mobId = ref.fMobTemplateId.getInt(obj);
                            if (mobId == 0 && currentMapId > 0) continue;
                            String objName = (String) ref.fObjName.get(obj);
                            if (objName != null && !objName.trim().isEmpty()) {
                                knownMobNameMap.put(mobId, objName.trim());
                                if (currentMapId > 1) knownMobMap.put(mobId, currentMapId);
                            }
                            String normMob = normalizeText(ref, objName);
                            if (normMob.length() >= 3 && !"smiley".equals(normMob) && normSeg.contains(normMob)) {
                                if (normMob.length() > bestLiveLen) {
                                    bestLiveLen = normMob.length();
                                    bestLiveId = mobId;
                                }
                            }
                        }
                        if (bestLiveId >= 0) return bestLiveId;
                    }
                }
            }

            // Buoc 2: Doi chieu tung doan van ban nhiem vu (uu tien qShort -> qHelp -> toan bo) voi Template & knownMobNameMap
            // Tuyet doi thu khop nguyen ban truoc khi xoa ten Map de tranh truong hop ten Map chua tu khoa trung ten quai
            for (String seg : textSegments) {
                String normSegRaw = normalizeText(ref, seg);
                if (normSegRaw.isEmpty()) continue;

                int matchedId = matchBestMonsterInNormText(ref, normSegRaw);
                if (matchedId >= 0) return matchedId;

                String normSegNoMap = stripMapNamesFromNormText(ref, normSegRaw);
                if (!normSegNoMap.equals(normSegRaw)) {
                    matchedId = matchBestMonsterInNormText(ref, normSegNoMap);
                    if (matchedId >= 0) return matchedId;
                }
            }

            // Buoc 3: Tu dien tu khoa du phong cho cac nhiem vu thu thap/hang ngay khong ghi ro hoac viet tat ten quai
            String normAll = normalizeText(ref, rawQuestText != null ? rawQuestText : textSegments.get(0));
            if (normAll.contains("caoxanh") || (normAll.contains("duocthao") && (currentMapId == 12 || knownMobMap.containsKey(14)))) {
                return 14; // Cao xanh (ID=14, Lv=25) tai Map 12
            }
        } catch (Throwable ignored) {}
        return -1;
    }

    private static String stripMapNamesFromNormText(RefCache ref, String normText) {
        if (normText == null || normText.isEmpty() || ref == null || ref.fMapNames == null) return normText;
        String result = normText;
        try {
            String[] mapNames = (String[]) ref.fMapNames.get(null);
            if (mapNames != null) {
                for (int mId = 0; mId < mapNames.length; mId++) {
                    if (!VALID_WORLD_MAPS.contains(mId)) continue;
                    String normMap = normalizeText(ref, mapNames[mId]);
                    if (normMap.length() >= 5 && !normMap.startsWith("map")) {
                        result = result.replace(normMap, " ");
                    }
                }
            }
        } catch (Throwable ignored) {}
        return result;
    }

    private static int matchBestMonsterInNormText(RefCache ref, String normText) {
        int bestMobId = -1;
        int bestLen = 0;
        int bestPos = -1;

        for (int mobId = 1; mobId <= 160; mobId++) {
            String mName = getMonsterTemplateName(ref, mobId);
            if (mName == null || mName.isEmpty()) continue;
            String normMob = normalizeText(ref, mName);
            if (normMob.length() < 3 || "smiley".equals(normMob)) continue;

            int idx = normText.lastIndexOf(normMob);
            if (idx >= 0) {
                if (normMob.length() > bestLen || (normMob.length() == bestLen && idx > bestPos)) {
                    bestLen = normMob.length();
                    bestPos = idx;
                    bestMobId = mobId;
                }
            }
        }
        return bestMobId;
    }

    private static void updateQuestProgressTracker(
            int questId, int stepMobId, int qType, boolean isBossQuest, int playerLevel,
            int currentTotalProgress, int currentMapId, int attackingMobTemplateId, int currentMobHp) {
        long now = System.currentTimeMillis();
        if (questId != lastTrackedQuestId || stepMobId != lastTrackedStepMobId) {
            if (questId != lastTrackedQuestId) {
                exhaustedQuestTargets.clear();
            }
            lastTrackedQuestId = questId;
            lastTrackedStepMobId = stepMobId;
            lastTrackedProgress = currentTotalProgress;
            sameMobAttackCountWithoutProgress = 0;
            killsWithoutProgressCount = 0;
            lastAttackedMobHp = currentMobHp;
            lastMobDamageDealtTime = now;
            firstAttackTimeWithoutProgress = 0L;
            lastProgressIncreaseTime = now;
            return;
        }
        if (currentTotalProgress > lastTrackedProgress) {
            lastTrackedProgress = currentTotalProgress;
            sameMobAttackCountWithoutProgress = 0;
            killsWithoutProgressCount = 0;
            lastAttackedMobHp = currentMobHp;
            lastMobDamageDealtTime = now;
            firstAttackTimeWithoutProgress = now;
            lastProgressIncreaseTime = now;
            return;
        }
        if (attackingMobTemplateId >= 0) {
            if (attackingMobTemplateId == lastAttackedMobTemplateId && currentMapId == lastAttackedMapId) {
                sameMobAttackCountWithoutProgress++;
                if (firstAttackTimeWithoutProgress == 0L) firstAttackTimeWithoutProgress = now;

                if (lastAttackedMobHp > 0 && currentMobHp > lastAttackedMobHp) {
                    killsWithoutProgressCount++;
                }
                if (lastAttackedMobHp <= 0 || currentMobHp < lastAttackedMobHp) {
                    lastMobDamageDealtTime = now;
                }
                lastAttackedMobHp = currentMobHp;

                long noDamageTimeoutMs = isBossQuest ? 60000L : Math.max(25000L, playerLevel * 300L);
                boolean noDamageTooLong = (now - lastMobDamageDealtTime) >= noDamageTimeoutMs;
                boolean shouldExhaust = (qType == 0)
                        ? (killsWithoutProgressCount >= 12 || noDamageTooLong)
                        : (killsWithoutProgressCount >= 3 || (sameMobAttackCountWithoutProgress >= 30 && noDamageTooLong));

                if (shouldExhaust) {
                    exhaustedQuestTargets.add(currentMapId + ":" + attackingMobTemplateId);
                    if (stepMobId >= 0 && attackingMobTemplateId != stepMobId) {
                        exhaustedQuestTargets.add("*:" + attackingMobTemplateId);
                    }
                    lockedTargetMob = null;
                    System.err.println("[AUTO-QUEST] Qu\u00e1i ID=" + attackingMobTemplateId
                            + " t\u1ea1i Map " + currentMapId + " (kills=" + killsWithoutProgressCount
                            + ", noDmgMs=" + (now - lastMobDamageDealtTime)
                            + ") kh\u00f4ng t\u0103ng ti\u1ebfn \u0111\u1ed9 -> Chuy\u1ec3n m\u1ee5c ti\u00eau!");
                    sameMobAttackCountWithoutProgress = 0;
                    killsWithoutProgressCount = 0;
                    firstAttackTimeWithoutProgress = now;
                    lastMobDamageDealtTime = now;
                }
            } else {
                lastAttackedMobTemplateId = attackingMobTemplateId;
                lastAttackedMapId = currentMapId;
                sameMobAttackCountWithoutProgress = 1;
                killsWithoutProgressCount = 0;
                lastAttackedMobHp = currentMobHp;
                lastMobDamageDealtTime = now;
                firstAttackTimeWithoutProgress = now;
            }
        }
    }

    private static Set<Integer> inferAllMonsterIdsFromQuestText(RefCache ref, Object quest, String rawQuestText) {
        Set<Integer> result = new HashSet<>();
        if (ref == null) return result;
        try {
            String sShort = (quest != null && ref.fQShort != null) ? (String) ref.fQShort.get(quest) : "";
            String sHelp = (quest != null && ref.fQHelp != null) ? (String) ref.fQHelp.get(quest) : "";
            String combined = (sShort != null ? sShort : "") + " " + (sHelp != null ? sHelp : "");
            if (combined.trim().isEmpty() && rawQuestText != null) combined = rawQuestText;
            String normText = stripMapNamesFromNormText(ref, normalizeText(ref, combined));
            if (normText.isEmpty()) normText = normalizeText(ref, combined);
            for (int mobId = 1; mobId <= 160; mobId++) {
                String mName = getMonsterTemplateName(ref, mobId);
                if (mName == null || mName.isEmpty()) continue;
                String normMob = normalizeText(ref, mName);
                if (normMob.length() < 3 || "smiley".equals(normMob)) continue;
                if (normText.contains(normMob)) {
                    result.add(mobId);
                }
            }
        } catch (Throwable ignored) {}
        return result;
    }

    private static boolean isTargetMobPresentOnCurrentMap(
            RefCache ref, int effectiveMobId, String normTargetName, String normQuestShort, int currentMapId) {
        if (ref == null || currentMapId <= 1 || ref.fVecActors == null) return false;
        try {
            Object vecActors = ref.fVecActors.get(null);
            if (vecActors == null) return false;
            Method[] vm = getVectorMethods(vecActors);
            if (vm == null) return false;
            int count = (Integer) invokeMethod(vm[0], vecActors);
            for (int i = 0; i < count; i++) {
                Object obj = invokeMethod(vm[1], vecActors, i);
                if (obj == null || !ref.clsMonsterBase.isInstance(obj)) continue;
                if (ref.fIsDead.getBoolean(obj) || ref.fState.getInt(obj) == 4 || ref.fHp.getInt(obj) <= 0) continue;
                int mobId = ref.fMobTemplateId.getInt(obj);
                if (mobId == 0 && currentMapId > 0) continue;
                if (exhaustedQuestTargets.contains(currentMapId + ":" + mobId)
                        || exhaustedQuestTargets.contains("*:" + mobId)) {
                    continue;
                }
                String objName = (String) ref.fObjName.get(obj);
                if (objName != null && !objName.trim().isEmpty()) {
                    knownMobNameMap.put(mobId, objName.trim());
                    knownMobMap.putIfAbsent(mobId, currentMapId);
                }
                String normObj = normalizeText(ref, objName);
                if (effectiveMobId >= 0) {
                    if (mobId == effectiveMobId || (!normTargetName.isEmpty() && normObj.equals(normTargetName))) {
                        return true;
                    }
                } else {
                    if ((!normTargetName.isEmpty() && normObj.equals(normTargetName))
                            || (normObj.length() >= 3 && !normQuestShort.isEmpty() && normQuestShort.contains(normObj))) {
                        return true;
                    }
                }
            }
        } catch (Throwable ignored) {}
        return false;
    }
private static int[] getStorylineMapsForNpc(
            RefCache ref, int npcId, int qId, String normQuestName, String normQuestAllText, int playerLevel) {
        // 1. Benjamin (ID = -19): Chuong Kho Bau o Khu phia Tay (Map 34), hoac Cua Bien (Map 19) / TP Kho Bau (Map 33)
        if (npcId == -19 || Math.abs(npcId) == 19 || normQuestName.contains("benjamin") || normQuestAllText.contains("benjamin")) {
            if (normQuestAllText.contains("cuabien")) return new int[]{19, 34, 33};
            return new int[]{34, 33, 20, 19};
        }

        // 2. NPC ID = -7: Aman (Map 1 - Lang Soi Trang) vs Amin (Map 33 - Thanh Pho Kho Bau)
        if (npcId == -7 || Math.abs(npcId) == 7) {
            if (normQuestAllText.contains("amin") || normQuestAllText.contains("khobau")
                    || normQuestAllText.contains("samac") || normQuestAllText.contains("hammo")
                    || normQuestAllText.contains("khu") || playerLevel >= 40) {
                return new int[]{33, 1};
            }
            return new int[]{1, 33};
        }

        // 3. Emma (-20), Blackeye (-21), Alisama (-22) tai Thanh Pho Kho Bau (Map 33) va cac map nhanh Sa Mac / Ham Mo
        if (npcId == -20 || npcId == -21 || npcId == -22) {
            return new int[]{33, 34, 35, 29, 20};
        }

        // 4. Linh canh / NPC xuat hien o nhieu map theo chuong
        if (npcId == -25 || npcId == -26) return new int[]{33, 34, 35};
        if (npcId == -29 || npcId == -30 || npcId == -31) return new int[]{1, 0, 7};
        if (npcId == -35 || npcId == 35 || npcId == -34 || npcId == 34) return new int[]{7, 18, 1};
        if (npcId == -59 || npcId == 59) return new int[]{17, 16, 15, 1};
        if (npcId == -9 || npcId == 9) return new int[]{12, 11, 13, 14, 2, 19, 1};
        if (npcId == -54 || npcId == 54) return new int[]{19, 33, 67, 1};
        if (npcId == -71 || npcId == 71) return new int[]{67, 73, 19, 33};

        // 5. NPC co dinh o Lang Nho (Map 0) va Lang Soi Trang (Map 1)
        if (npcId == -1) return new int[]{0, 1};
        if (isTownMainNpc(npcId) || npcId == -36 || npcId == -37 || npcId == -69 || npcId == -81) {
            return new int[]{1};
        }

        return getCandidateMapsForNpc(npcId, normQuestAllText);
    }
    private static int resolveQuestTargetMapId(
            RefCache ref, Object quest, int effectiveMobId, int maxProgress, int currentMapId) {
        try {
            String sShort = (String) ref.fQShort.get(quest);
            String sHelp = (String) ref.fQHelp.get(quest);
            byte qType = ref.fQType.getByte(quest);
            int qId = ref.fQId.getInt(quest);
            int npcId = ref.fQNpcId.getInt(quest);
            boolean isBossQuest = (qType == 1 && maxProgress == 1);

            String allQuestText = extractAllTextFromQuest(ref, quest);
            String normAll = normalizeText(ref, allQuestText);
            String normShort = normalizeText(ref, (sShort != null ? sShort : "") + " " + (sHelp != null ? sHelp : ""));
            String targetMobName = getMonsterTemplateName(ref, effectiveMobId);
            String normTargetName = normalizeText(ref, targetMobName);

            ensureQuestScriptLoaded();
            QStep preStep = questScriptByQuestId.get(qId);
            if (preStep != null && preStep.mobMap >= 0 && !searchedEmptyMaps.contains(preStep.mobMap)) {
                return preStep.mobMap;
            }

            if (currentMapId > 1 && isTargetMobPresentOnCurrentMap(
                    ref, effectiveMobId, normTargetName, (effectiveMobId < 0 ? normShort : ""), currentMapId)) {
                searchedEmptyMaps.remove(currentMapId);
                return currentMapId;
            }

            if (effectiveMobId >= 0 && knownMobMap.containsKey(effectiveMobId)) {
                int learnedMap = knownMobMap.get(effectiveMobId);
                if (!searchedEmptyMaps.contains(learnedMap)) return learnedMap;
            }

            int specialMap = getKnownStoryBossOrMetaphorMap(ref, effectiveMobId, targetMobName, normAll);
            if (specialMap >= 0 && !searchedEmptyMaps.contains(specialMap)) {
                return selectNextMapInStoryBranch(specialMap, isBossQuest);
            }

            int levelDestMap = -1;
            int mobLevel = getMonsterTemplateLevel(ref, effectiveMobId);
            if (mobLevel > 0) {
                levelDestMap = getFarmMapForLevel(ref, mobLevel);
            }

            int textDestMap = matchLastDestinationMapFromText(ref, sShort);
            if (textDestMap < 0) textDestMap = matchLastDestinationMapFromText(ref, sHelp);
            if (textDestMap < 0) textDestMap = matchLastDestinationMapFromText(ref, allQuestText);

            if (!isBossQuest && effectiveMobId >= 0 && levelDestMap > 1 && !searchedEmptyMaps.contains(levelDestMap)) {
                return levelDestMap;
            }

            if (!isBossQuest && textDestMap > 1 && !searchedEmptyMaps.contains(textDestMap)) {
                return textDestMap;
            }

            int anchorMap = (textDestMap > 1) ? textDestMap : levelDestMap;
            if (anchorMap < 0) {
                Integer originMap = questOriginMap.get(qId);
                if (originMap != null && originMap > 1) {
                    anchorMap = originMap;
                } else {
                    Integer npcMap = knownNpcMap.get(npcId);
                    if (npcMap != null && npcMap > 1) {
                        anchorMap = npcMap;
                    } else if (currentMapId > 1 && !searchedEmptyMaps.contains(currentMapId)) {
                        anchorMap = currentMapId;
                    } else if (lastKnownPlayerLevel > 1) {
                        anchorMap = getFarmMapForLevel(ref, lastKnownPlayerLevel);
                    } else {
                        anchorMap = 3;
                    }
                }
            }

           
            int targetMap = selectNextMapInStoryBranch(anchorMap, isBossQuest);
            // Kiem tra gioi han level cua map dac biet (Thanh pho kho bau Lv.40, Thanh pho cang Lv.80...)
            int reqMapLv = getMinLevelForSpecialMap(targetMap);
            
            return targetMap;
        } catch (Throwable ignored) {}
        return currentMapId > 1 ? currentMapId : 3;
    }
    private static int resolveNpcReportMapId(
            RefCache ref, Object quest, int npcId, int qId, int currentMapId) {
        // [FIX 1] Neu dang chay Kich ban va co chi dinh Map NPC -> Dung ngay 100%, khong tu doan!
        if (questScriptMode && scriptOverrideQuestId == qId && scriptOverrideNpcMap >= 0) {
            return scriptOverrideNpcMap;
        }
        ensureQuestScriptLoaded();
        QStep preNpcStep = questScriptByQuestId.get(qId);
        if (preNpcStep != null) {
            if (preNpcStep.turnNpc == npcId && preNpcStep.turnMap >= 0 && !searchedMissingNpcMaps.contains(preNpcStep.turnMap)) {
                return preNpcStep.turnMap;
            }
            if (preNpcStep.giveNpc == npcId && preNpcStep.giveMap >= 0 && !searchedMissingNpcMaps.contains(preNpcStep.giveMap)) {
                return preNpcStep.giveMap;
            }
            if (preNpcStep.turnMap >= 0 && !searchedMissingNpcMaps.contains(preNpcStep.turnMap)) {
                return preNpcStep.turnMap;
            }
        }

        if (isTownMainNpc(npcId)) return 1;
        if (npcId == -1) return 0;

        if (currentMapId >= 0 && (System.currentTimeMillis() - currentMapEnterTime > 3500L)) {
            searchedMissingNpcMaps.add(currentMapId);
        }

        String qName = "";
        String sShort = "";
        String sHelp = "";
        try {
            if (quest != null) {
                if (ref.fQName != null) qName = (String) ref.fQName.get(quest);
                if (ref.fQShort != null) sShort = (String) ref.fQShort.get(quest);
                if (ref.fQHelp != null) sHelp = (String) ref.fQHelp.get(quest);
            }
        } catch (Throwable ignored) {}

        String allQuestText = extractAllTextFromQuest(ref, quest);
        String normName = normalizeText(ref, qName);
        String normAll = normalizeText(ref, allQuestText);

        // UU TIEN 1: Doc truc tiep ten Map trong mo ta cot truyen cua nhiem vu (qShort -> qHelp -> allQuestText)
        int textMap = matchLastDestinationMapFromText(ref, sShort);
        if (textMap < 0) textMap = matchLastDestinationMapFromText(ref, sHelp);
        if (textMap < 0) textMap = matchLastDestinationMapFromText(ref, allQuestText);
        if (textMap >= 0 && !searchedMissingNpcMaps.contains(textMap)) {
            return textMap;
        }

        // UU TIEN 2: Neu nhiem vu nay da ghi nhan Map goc luc nhan (questOriginMap theo qId)
        Integer originMap = questOriginMap.get(qId);
        if (originMap != null && originMap >= 0 && !searchedMissingNpcMaps.contains(originMap)) {
            return originMap;
        }

        // UU TIEN 3: Tra bang phan nhanh NPC theo cot truyen (Benjamin Map 34, Aman Map 1 / Amin Map 33...)
        int[] storyCandidates = getStorylineMapsForNpc(
                ref, npcId, qId, normName, normAll, Math.max(1, lastKnownPlayerLevel));
        for (int mapId : storyCandidates) {
            if (!searchedMissingNpcMaps.contains(mapId)) {
                return mapId;
            }
        }

        // UU TIEN 4: Bo nho knownNpcMap (chi dung khi Map do chua bi danh dau tim truot)
        Integer cachedMap = knownNpcMap.get(npcId);
        if (cachedMap != null && cachedMap >= 0 && !searchedMissingNpcMaps.contains(cachedMap)) {
            return cachedMap;
        }

        // Neu da quet het cac ung vien ma chua thay, reset danh sach de thu lai (bo qua map hien tai)
        searchedMissingNpcMaps.clear();
        if (currentMapId >= 0) searchedMissingNpcMaps.add(currentMapId);
        for (int mapId : storyCandidates) {
            if (mapId != currentMapId) return mapId;
        }
        return (currentMapId == 33) ? 34 : 33;
    }

    private static int getMinLevelForSpecialMap(int mapId) {
        switch (mapId) {
            // Thanh Pho Kho Bau (Map 33) va toan bo cac map phai di qua cong Map 33 -> Yeu cau Lv.40
            case 20: case 23: case 24:
            case 29: case 30: case 31: case 32:
            case 33: case 34: case 35:
                return 40;
            // Thanh Pho Cang (Map 67) va toan bo cac map Vinh Caribe / Me Cung -> Yeu cau Lv.80
            case 63: case 64: case 65: case 66:
            case 67: case 68: case 69: case 70: case 71: case 72:
            case 73: case 74: case 75: case 76: case 77: case 78: case 79:
                return 80;
            // Khu vuc Nui Tuyet cap cao -> Yeu cau Lv.100
            case 92: case 93: case 94: case 95: case 96: case 97: case 98:
                return 100;
            default:
                return -1;
        }
    }
    private static int[] getNextPatrolPointForMap(RefCache ref, int mapId, int px, int py) {
        List<int[]> points = new ArrayList<>();
        if (mapId == 1) {
            points.add(new int[]{360, 240});
            points.add(new int[]{220, 240});
            points.add(new int[]{500, 240});
            points.add(new int[]{360, 380});
        } else if (mapId == 0) {
            points.add(new int[]{240, 200});
            points.add(new int[]{360, 240});
        }

        if (ref != null && ref.fDefaultSpots != null) {
            try {
                int[][] spots = (int[][]) ref.fDefaultSpots.get(null);
                if (spots != null) {
                    for (int[] s : spots) {
                        if (s != null && s.length >= 6 && s[2] == mapId) {
                            points.add(new int[]{s[4], s[5]});
                        }
                    }
                }
            } catch (Throwable ignored) {}
        }

        int mapW = 720;
        int mapH = 720;
        if (ref != null && ref.fLoadMap != null) {
            try {
                Object loadMap = ref.fLoadMap.get(null);
                if (loadMap != null) {
                    if (cachedLoadMapWField == null || cachedLoadMapHField == null) {
                        cachedLoadMapWField = loadMap.getClass().getDeclaredField("n0007");
                        cachedLoadMapHField = loadMap.getClass().getDeclaredField("n0008");
                        cachedLoadMapWField.setAccessible(true);
                        cachedLoadMapHField.setAccessible(true);
                    }
                    int w = cachedLoadMapWField.getInt(loadMap);
                    int h = cachedLoadMapHField.getInt(loadMap);
                    if (w > 200) mapW = w;
                    if (h > 200) mapH = h;
                }
            } catch (Throwable ignored) {}
        }

        points.add(new int[]{mapW / 2, mapH / 2});
        points.add(new int[]{mapW / 3, mapH / 3});
        points.add(new int[]{(mapW * 2) / 3, mapH / 3});
        points.add(new int[]{mapW / 2, (mapH * 2) / 3});

        int[] chosen = points.get(lastPatrolIndex % points.size());
        if (Math.hypot(chosen[0] - px, chosen[1] - py) < 80) {
            lastPatrolIndex = (lastPatrolIndex + 1) % points.size();
            chosen = points.get(lastPatrolIndex);
        }
        return chosen;
    }

    private static Object findNpcOnCurrentMap(RefCache ref, int npcId) {
        if (ref == null || System.currentTimeMillis() - currentMapEnterTime < 250L) return null;

        if (ref.mFindObjScr != null) {
            try {
                Object npc = invokeMethod(ref.mFindObjScr, null, npcId, (byte) 2);
                if (npc != null) return npc;
                if (npcId != 0) {
                    npc = invokeMethod(ref.mFindObjScr, null, -npcId, (byte) 2);
                    if (npc != null) return npc;
                }
            } catch (Throwable ignored) {}
        }

        try {
            Object vecActors = (ref.fVecActors != null) ? ref.fVecActors.get(null) : null;
            if (vecActors == null) return null;

            Method[] vm = getVectorMethods(vecActors);
            if (vm == null) return null;

            int count = (Integer) invokeMethod(vm[0], vecActors);
            for (int i = 0; i < count; i++) {
                Object obj = invokeMethod(vm[1], vecActors, i);
                if (obj == null) continue;
                int type = ((Number) ref.fObjType.get(obj)).intValue();
                if (type == 2) {
                    int id = ((Number) ref.fNpcId.get(obj)).intValue();
                    if (id == npcId || (npcId != 0 && Math.abs(id) == Math.abs(npcId))) return obj;
                }
            }
        } catch (Throwable ignored) {}
        return null;
    }

    // =========================================================================
    // P0-3 & P0-4: GOI DUNG n0193.n0001(n0199) VA DOC KET QUA n0197
    // =========================================================================
    private static boolean startNavigatorRouteRequest(
            RefCache ref, int destMapId, boolean hasPos, int tx, int ty,
            int stopDist, boolean useTeleport, String reasonTag) {
        if (ref == null || ref.mGetNav == null || ref.mStartRoute == null || ref.ctorRouteReq == null) {
            return false;
        }
        try {
            Object token = ensureControlToken(ref);
            if (token == null) return false;

            Object navInstance = invokeMethod(ref.mGetNav, null);
            if (navInstance == null) return false;
            if (ref.mEnsureGraph != null) invokeMethod(ref.mEnsureGraph, navInstance);

            Object routeReq = invokeCtor(ref.ctorRouteReq,
                    "AUTO_QUEST", destMapId, hasPos, tx, ty, stopDist, useTeleport, token);

            Object resObj = invokeMethod(ref.mStartRoute, navInstance, routeReq);
            boolean accepted = false;
            String msg = "";
            if (resObj != null && ref.fResOk != null && ref.fResMsg != null) {
                accepted = ref.fResOk.getBoolean(resObj);
                msg = (String) ref.fResMsg.get(resObj);
                if (msg == null) msg = "";
            }
            lastNavResultMsg = accepted ? ("OK:" + msg) : ("REJECT:" + msg);

            if (accepted) {
                logRateLimited("NAV_START_" + destMapId + "_" + useTeleport, 5000L,
                        "[AUTO-QUEST] B\u1eaft \u0111\u1ea7u l\u1ed9 tr\u00ecnh (" + reasonTag + ") -> Map " + destMapId
                                + (hasPos ? (" (" + tx + "," + ty + ")") : "")
                                + " | \u0110\u00e1 d\u1ecbch chuy\u1ec3n=" + useTeleport
                                + (msg.isEmpty() ? "" : " | Ph\u1ea3n h\u1ed3i=" + msg));
                if (ref.mNavOnInGame != null) invokeMethod(ref.mNavOnInGame, navInstance);
                if (ref.mNavTick != null) invokeMethod(ref.mNavTick, navInstance);
                dirty = true;
                return true;
            }

            // Xu ly tung ly do tu choi trong n0197.n0002
            logRateLimited("NAV_REJECT", 5000L,
                    "[AUTO-QUEST] L\u1ed9 tr\u00ecnh t\u1edbi Map " + destMapId + " b\u1ecb t\u1eeb ch\u1ed1i (" + reasonTag
                            + "): '" + msg + "' (useTeleport=" + useTeleport + ", hasPos=" + hasPos + ")");

            if (msg.contains("Caller khong con quyen") || msg.contains("quyen dieu khien")
                    || msg.contains("dich vu uu tien cao hon")) {
                autoQuestRouteToken = null;
                currentRoutingTargetMap = -1;
                lastNavRejectBackoffUntil = System.currentTimeMillis() + 2500L;
            } else if (msg.contains("khong hop le")) {
                if (useTeleport) {
                    useTeleportForCurrentTrip = false;
                } else if (hasPos) {
                    // Thu lai khong ep toa do dich
                    Object fallbackReq = invokeCtor(ref.ctorRouteReq,
                            "AUTO_QUEST", destMapId, false, -1, -1, stopDist, false, token);
                    Object fbRes = invokeMethod(ref.mStartRoute, navInstance, fallbackReq);
                    if (fbRes != null && ref.fResOk != null && ref.fResOk.getBoolean(fbRes)) {
                        return true;
                    }
                }
            }
        } catch (Throwable t) {
            logReflectErrorOnce("startNavigatorRouteRequest", t);
        }
        return false;
    }

    private static void tickNavigator(RefCache ref) {
        if (ref == null || ref.mGetNav == null) return;
        try {
            Object navInstance = invokeMethod(ref.mGetNav, null);
            if (navInstance != null) {
                if (ref.mNavOnInGame != null) invokeMethod(ref.mNavOnInGame, navInstance);
                if (ref.mNavTick != null) invokeMethod(ref.mNavTick, navInstance);
                dirty = true;
            }
        } catch (Throwable ignored) {}
    }

    /**
     * P0-3 Fix: Tien lai gan muc tieu xa hon 48px bang n0199(mapId, hasPos=true, tx, ty)
     * kem dieu khien phim huong du phong neu sau 1.5s vi tri (n0074, n0075) chua nhich.
     */
    private static void movePlayerCloseToTarget(
            RefCache ref, Object targetObj, Object player, int currentMapId) {
        if (ref == null || targetObj == null || player == null) return;
        try {
            if (ref.fFocus != null) ref.fFocus.set(null, targetObj);
            int px = ref.fX.getInt(player);
            int py = ref.fY.getInt(player);
            int tx = ref.fX.getInt(targetObj);
            int ty = ref.fY.getInt(targetObj);
            int dist = (int) Math.hypot(tx - px, ty - py);

            updatePositionProgress(px, py);
            long now = System.currentTimeMillis();
            long stalledMs = now - lastPositionChangeTime;
            boolean posStalled = (stalledMs > 1200L);

            // Neu muc tieu la quai va o trong tam <= 160px, goi ho tro tan cong/tiep can cua n0213.n0001
            if (dist <= 160 && ref.clsMonsterBase != null && ref.clsMonsterBase.isInstance(targetObj)) {
                try {
                    Object token = ensureControlToken(ref);
                    if (token != null && ref.mCombatAttack != null) {
                        invokeMethod(ref.mCombatAttack, null, token, targetObj, 0, FULL_SKILL_PRIORITY);
                    }
                } catch (Throwable ignored) {}
            }

            if (posStalled && dist > 48) {
                // Khi n0193 bi vuong dia hinh > 1.2s, ngat n0193 va dung phim huong truc tiep kem lach tuong
                if (currentRoutingTargetMap >= 0) {
                    try {
                        if (ref.mGetNav != null && ref.mStopRoute != null && autoQuestRouteToken != null) {
                            Object navInstance = invokeMethod(ref.mGetNav, null);
                            if (navInstance != null) {
                                invokeMethod(ref.mStopRoute, navInstance, autoQuestRouteToken, "L\u00e1ch v\u1eadt c\u1ea3n");
                            }
                        }
                    } catch (Throwable ignored) {}
                    currentRoutingTargetMap = -1;
                }
                stepDirectionalKeyTowards(px, py, tx, ty, stalledMs > 2400L);
                return;
            }

            releaseAllDirectionalKeys();
            if (currentMapId >= 0) {
                // Chi tao yeu cau dinh tuyen moi khi muc tieu dich chuyen > 68px va cach lan dinh tuyen truoc >= 2.2s
                if ((Math.hypot(tx - currentRoutingTargetX, ty - currentRoutingTargetY) > 68 && (now - lastRouteTime > 1800L))
                        || currentRoutingTargetMap != currentMapId
                        || (now - lastRouteTime > 3200L)) {
                    lastRouteTime = now;
                    currentRoutingTargetMap = currentMapId;
                    currentRoutingTargetX = tx;
                    currentRoutingTargetY = ty;
                    startNavigatorRouteRequest(ref, currentMapId, true, tx, ty, 28, false,
                            "Ti\u1ebfn s\u00e1t m\u1ee5c ti\u00eau c\u00e1ch " + dist + "px");
                } else {
                    tickNavigator(ref);
                }
            }
        } catch (Throwable t) {
            logReflectErrorOnce("movePlayerCloseToTarget", t);
        }
    }

    private static void stepDirectionalKeyTowards(int px, int py, int tx, int ty) {
        stepDirectionalKeyTowards(px, py, tx, ty, false);
    }

    private static void stepDirectionalKeyTowards(int px, int py, int tx, int ty, boolean slideObstacle) {
        try {
            int dx = tx - px;
            int dy = ty - py;
            int desiredKey;
            boolean useHorizontal = Math.abs(dx) >= Math.abs(dy);
            if (slideObstacle) {
                wallSlideToggle = (wallSlideToggle + 1) % 4;
                if (wallSlideToggle >= 2) {
                    useHorizontal = !useHorizontal;
                }
            }
            if (useHorizontal) {
                desiredKey = (dx >= 0) ? -4 : -3; // Phai (-4) / Trai (-3)
            } else {
                desiredKey = (dy >= 0) ? -2 : -1; // Xuong (-2) / Len (-1)
            }
            if (activeDirectionalKey != 0 && activeDirectionalKey != desiredKey) {
                platform.keyReleased(activeDirectionalKey);
            }
            activeDirectionalKey = desiredKey;
            platform.keyPressed(desiredKey);
            dirty = true;
        } catch (Throwable ignored) {}
    }

    private static void updatePositionProgress(int px, int py) {
        if (lastPx < 0 || lastPy < 0 || Math.abs(px - lastPx) >= 3 || Math.abs(py - lastPy) >= 3) {
            lastPx = px;
            lastPy = py;
            lastPositionChangeTime = System.currentTimeMillis();
            consecutiveStuckRecoveries = 0;
        }
    }

    private static void triggerAutoRouteToQuest(
            RefCache ref, Object quest, Object player, int targetMapId, int mobCountOnMap) {
        try {
            int currentMapId = getCurrentMapId(ref);
            if (currentMapId < 0)
                return;

            syncMapChangeWithNavigator(ref, currentMapId);

            if (targetMapId < 0 || !VALID_WORLD_MAPS.contains(targetMapId))
                targetMapId = 1;
            if (currentTripGoalMap != targetMapId)
                resetTeleportTripState(targetMapId, currentMapId);

            int px = ref.fX.getInt(player);
            int py = ref.fY.getInt(player);
            updatePositionProgress(px, py);

            long now = System.currentTimeMillis();
            boolean isSameMap = (targetMapId == currentMapId);

            if (isSameMap) {
                long timeOnMap = now - Math.max(currentMapEnterTime, lastTargetSeenOrQuestChangeTime);
                boolean isBossRoom = (getBranchBossRoom(currentMapId) == currentMapId);
                boolean hasKnownTargetHere = (trackedTargetMobId >= 0
                        && Integer.valueOf(currentMapId).equals(knownMobMap.get(trackedTargetMobId)));

                long waitLimit = (isBossRoom || hasKnownTargetHere) ? 45000L : ((mobCountOnMap > 0) ? 10000L : 12000L);
                // [FIX 2A] Khong tu y danh dau Map trong khi dang chay Kich ban co dinh
                if (!questScriptMode && trackedQuestId != -999 && timeOnMap > waitLimit) {
                    markMapSearchedEmpty(currentMapId);
                    searchedMissingNpcMaps.add(currentMapId);
                    logRateLimited("MAP_EMPTY_" + currentMapId, 5000L,
                            "[AUTO-QUEST] Map " + currentMapId + " kh\u00f4ng c\u00f3 m\u1ee5c ti\u00eau sau "
                                    + (timeOnMap / 1000) + "s -> Chuy\u1ec3n sang Map ti\u1ebfp theo...");
                    return;
                }

                int[] pt = getNextPatrolPointForMap(ref, currentMapId, px, py);
                if (now - lastRouteTime > 2400L) {
                    lastRouteTime = now;
                    currentRoutingTargetMap = currentMapId;
                    currentRoutingTargetX = pt[0];
                    currentRoutingTargetY = pt[1];
                    startNavigatorRouteRequest(ref, currentMapId, true, pt[0], pt[1], 24, false,
                            "\u0110i tu\u1ea7n trong Map " + currentMapId);
                } else {
                    tickNavigator(ref);
                }

                if (now - lastPositionChangeTime > 2000L) {
                    stepDirectionalKeyTowards(px, py, pt[0], pt[1]);
                } else {
                    releaseAllDirectionalKeys();
                }
                return;
            }

            releaseAllDirectionalKeys();
            int bestStoneMap = selectBestTeleportStoneForTrip(currentMapId, targetMapId);

            if (currentStoneTargetMap != bestStoneMap) {
                currentStoneTargetMap = bestStoneMap;
                currentStoneAttemptStartTime = now;
            }

            // [B12] Timeout 12s khi dung Da dich chuyen (khong khoa tram neu dang giao tranh hoac vua chet)
            boolean inCombatOrRecentlyDead = (lockedTargetMob != null) || (now - lastDeathTimeMs < 15000L);
            if (bestStoneMap >= 0
                    && mapsWithTeleportStone.contains(currentMapId)
                    && currentStoneAttemptStartTime > 0
                    && !inCombatOrRecentlyDead
                    && (now - currentStoneAttemptStartTime > 12000L)) {
                markTeleportMapLocked(bestStoneMap);
                currentStoneAttemptStartTime = now;
                stopNavOnly(ref);
                tapKeyHelper(-7, 30, 60);
                lastRouteTime = 0;
                currentRoutingTargetMap = -1;

                int nextCandidateStone = selectBestTeleportStoneForTrip(currentMapId, targetMapId);
                if (nextCandidateStone >= 0) {
                    logRateLimited("TELE_FALLBACK", 5000L,
                            "[AUTO-QUEST] Tr\u1ea1m \u0110\u00e1 Map " + bestStoneMap
                                    + " ch\u01b0a m\u1edf kh\u00f3a -> \u0110\u1ed5i sang tr\u1ea1m g\u1ea7n nh\u1ea5t ti\u1ebfp theo: Map "
                                    + nextCandidateStone);
                } else {
                    logRateLimited("TELE_WALK_FALLBACK", 5000L,
                            "[AUTO-QUEST] Tr\u1ea1m \u0110\u00e1 Map " + bestStoneMap
                                    + " ch\u01b0a m\u1edf kh\u00f3a -> Chuy\u1ec3n sang ch\u1ea1y b\u1ed9 li\u00ean Map "
                                    + currentMapId + " -> " + targetMapId);
                }
                bestStoneMap = nextCandidateStone;
                currentStoneTargetMap = bestStoneMap;
            } else if (inCombatOrRecentlyDead) {
                currentStoneAttemptStartTime = now;
            }

            boolean useTeleport = (bestStoneMap >= 0);
            int navDestinationMap = useTeleport ? bestStoneMap : targetMapId;

            int[] spot = getFarmSpotForMap(ref, navDestinationMap);
            boolean hasTargetPos = (!useTeleport && spot != null);
            int targetX = hasTargetPos ? spot[0] : -1;
            int targetY = hasTargetPos ? spot[1] : -1;

            boolean isPlayerStuck = ((now - lastPositionChangeTime > 6000L) && (now - lastRouteTime > 6000L));
            boolean needStartNewRoute = (currentRoutingTargetMap != navDestinationMap)
                    || (currentRoutingTeleportMode != useTeleport)
                    || (lastRoutedFromMapId != currentMapId)
                    || isPlayerStuck;

            if (needStartNewRoute) {
                lastRouteTime = now;
                currentRoutingTargetMap = navDestinationMap;
                currentRoutingTeleportMode = useTeleport;
                lastRoutedFromMapId = currentMapId;
                if (useTeleport)
                    currentStoneAttemptStartTime = now;

                String modeDesc = useTeleport
                        ? ("\u0110\u00e1 d\u1ecbch chuy\u1ec3n Map " + currentMapId + " -> Tr\u1ea1m "
                                + navDestinationMap + " (\u0110\u00edch: " + targetMapId + ")")
                        : ("Ch\u1ea1y b\u1ed9 li\u00ean Map " + currentMapId + " -> Map " + targetMapId);
                startNavigatorRouteRequest(ref, navDestinationMap, hasTargetPos, targetX, targetY, 24, useTeleport,
                        modeDesc);
            } else {
                tickNavigator(ref);
            }
        } catch (Throwable e) {
            logReflectErrorOnce("triggerAutoRouteToQuest", e);
        }
    }
    // =========================================================================
    // HE THONG TU DONG DO KHU, DEM NGUOI TRANH BAI & CHUYEN SANG KHU VANG NHAT
    // =========================================================================

    /**
     * Đếm số lượng người chơi khác đang có mặt trên bản đồ và trong bán kính bãi quái (FARM_SPOT_RADIUS).
     * Trả về mảng 2 phần tử: {playersInSpot, totalOtherPlayersOnMap}
     */
    private static int[] countOtherPlayersAroundSpot(RefCache ref, Object player, int spotX, int spotY) {
        if (ref == null || player == null || ref.fVecActors == null) return new int[]{0, 0};
        int inSpot = 0;
        int onMap = 0;
        try {
            Object vecActors = ref.fVecActors.get(null);
            if (vecActors == null) return new int[]{0, 0};
            Method[] vm = getVectorMethods(vecActors);
            if (vm == null) return new int[]{0, 0};

            int px = ref.fX.getInt(player);
            int py = ref.fY.getInt(player);
            int anchorX = (spotX > 0) ? spotX : px;
            int anchorY = (spotY > 0) ? spotY : py;

            int count = (Integer) invokeMethod(vm[0], vecActors);
            for (int i = 0; i < count; i++) {
                Object obj = invokeMethod(vm[1], vecActors, i);
                if (obj == null || obj == player) continue;

                byte objType = ref.fObjType.getByte(obj);
                // Trong HSO: objType == 0 la Nguoi choi (Player), loai tru Quai (clsMonsterBase) va NPC (objType == 2)
                boolean isOtherPlayer = (objType == 0)
                        || (ref.clsPlayer != null && ref.clsPlayer.isInstance(obj)
                            && (ref.clsMonsterBase == null || !ref.clsMonsterBase.isInstance(obj)));
                if (!isOtherPlayer) continue;

                // Bo qua nguoi choi da chet
                if (ref.fIsDead != null && ref.fIsDead.getBoolean(obj)) continue;
                if (ref.fHp != null && ref.fHp.getInt(obj) <= 0) continue;

                onMap++;
                int ox = ref.fX.getInt(obj);
                int oy = ref.fY.getInt(obj);
                if (Math.hypot(ox - anchorX, oy - anchorY) <= FARM_SPOT_RADIUS) {
                    inSpot++;
                }
            }
        } catch (Throwable ignored) {}
        lastObservedPlayersInSpot = inSpot;
        lastObservedPlayersOnMap = onMap;
        return new int[]{inSpot, onMap};
    }

    /**
     * Đọc ID khu vực hiện tại từ LoadMap (n0038.n0017) hoặc GameScr (n0001.n0041)
     */
    private static int readCurrentZoneId(RefCache ref) {
        if (ref == null) return currentZoneId;
        try {
            if (ref.fLoadMap != null) {
                Object loadMap = ref.fLoadMap.get(null);
                if (loadMap != null) {
                    if (cachedCurrentZoneField != null) {
                        int z = ((Number) cachedCurrentZoneField.get(loadMap)).intValue();
                        if (z >= 0 && z < 60) {
                            currentZoneId = z;
                            return z;
                        }
                    }
                    // Tìm field byte/int lưu idArea trong LoadMap (ngoài mapId n0004 và kích thước n0007/n0008)
                    for (Field f : loadMap.getClass().getDeclaredFields()) {
                        if (Modifier.isStatic(f.getModifiers())) continue;
                        String fn = f.getName();
                        if ("n0004".equals(fn) || "n0007".equals(fn) || "n0008".equals(fn)) continue;
                        if (f.getType() == byte.class) {
                            f.setAccessible(true);
                            int val = f.getByte(loadMap) & 0xFF;
                            if (val >= 0 && val < 30) {
                                cachedCurrentZoneField = f;
                                currentZoneId = val;
                                return val;
                            }
                        }
                    }
                }
            }
        } catch (Throwable ignored) {}
        return currentZoneId;
    }

    /**
     * Đọc mảng trạng thái / số lượng người chơi ở từng khu (byte[] numPlayerInMap / statusArea) trong GameScr
     */
    private static byte[] readZonePopulationArray(RefCache ref) {
        if (ref == null || ref.clsGameScr == null) return null;
        try {
            if (cachedZoneArrayField != null) {
                Object arr = cachedZoneArrayField.get(null);
                if (arr instanceof byte[]) {
                    byte[] bArr = (byte[]) arr;
                    if (bArr.length >= 3 && bArr.length <= 60) return bArr;
                }
            }
            for (Field f : ref.clsGameScr.getDeclaredFields()) {
                if (!Modifier.isStatic(f.getModifiers()) || f.getType() != byte[].class) continue;
                f.setAccessible(true);
                byte[] bArr = (byte[]) f.get(null);
                if (bArr != null && bArr.length >= 5 && bArr.length <= 50) {
                    boolean valid = true;
                    for (byte b : bArr) {
                        if (b < -1 || b > 50) {
                            valid = false;
                            break;
                        }
                    }
                    if (valid) {
                        cachedZoneArrayField = f;
                        return bArr;
                    }
                }
            }
        } catch (Throwable ignored) {}
        return null;
    }

    /**
     * Tính toán và chọn ra Khu có ít người chơi nhất (hoặc khu vắng nhất chưa ghé qua gần đây)
     */
    private static int selectLeastCrowdedZone(RefCache ref, int curZone) {
        long now = System.currentTimeMillis();
        visitedCrowdedZones.entrySet().removeIf(e -> now >= e.getValue());

        byte[] zonePop = readZonePopulationArray(ref);
        if (zonePop != null && zonePop.length > 1) {
            int bestZone = -1;
            int minPop = Integer.MAX_VALUE;

            for (int z = 0; z < zonePop.length; z++) {
                if (z == curZone) continue;
                if (visitedCrowdedZones.containsKey(z)) continue;
                int pop = zonePop[z];
                if (pop < 0) continue; // Khu khóa hoặc không hợp lệ
                if (pop < minPop) {
                    minPop = pop;
                    bestZone = z;
                }
            }
            if (bestZone >= 0) return bestZone;
        }

        // Fallback thông minh: Xoay vòng qua các khu từ Khu 1..9 (ưu tiên khu chưa nằm trong visitedCrowdedZones)
        int maxFallbackZones = 9;
        for (int offset = 1; offset <= maxFallbackZones; offset++) {
            int candidate = (curZone + offset) % maxFallbackZones;
            if (candidate != curZone && !visitedCrowdedZones.containsKey(candidate)) {
                return candidate;
            }
        }
        visitedCrowdedZones.clear();
        return (curZone + 1) % maxFallbackZones;
    }

    /**
     * Gửi yêu cầu mở danh sách khu hoặc chuyển sang khu targetZoneId
     */
    private static boolean performZoneSwitch(RefCache ref, Object player, int currentMapId, int targetZoneId, String reason) {
        if (ref == null || targetZoneId < 0) return false;
        long now = System.currentTimeMillis();
        try {
            // 1. Ưu tiên gọi hàm đổi khu có sẵn trong Mod Helper (n0001.n0213) nếu có
            if (cachedChangeZoneMethod == null && ref.clsHelper != null) {
                for (Method m : ref.clsHelper.getDeclaredMethods()) {
                    if (!Modifier.isStatic(m.getModifiers())) continue;
                    String mn = m.getName().toLowerCase();
                    Class<?>[] pt = m.getParameterTypes();
                    if ((mn.contains("zone") || mn.contains("area") || mn.contains("khu"))
                            && pt.length == 1 && (pt[0] == int.class || pt[0] == byte.class)) {
                        m.setAccessible(true);
                        cachedChangeZoneMethod = m;
                        break;
                    }
                }
            }

            if (cachedChangeZoneMethod != null) {
                Class<?> paramType = cachedChangeZoneMethod.getParameterTypes()[0];
                Object arg = (paramType == byte.class) ? Byte.valueOf((byte) targetZoneId) : Integer.valueOf(targetZoneId);
                invokeMethod(cachedChangeZoneMethod, null, arg);
                currentZoneId = targetZoneId;
                lastZoneSwitchTime = now;
                waitingForZoneListResponse = false;
                System.err.println("[AUTO-ZONE] (" + reason + ") \u0110\u00e3 g\u1ecdi Mod Helper chuy\u1ec3n sang Khu " + (targetZoneId + 1));
                dirty = true;
                return true;
            }

            // 2. Tương tác với Cột chuyển khu (NPC ID = -32) hoặc gửi gói đổi khu qua GlobalService (n0001.n0044)
            Object zonePoleNpc = findNpcOnCurrentMap(ref, -32);
            if (zonePoleNpc != null && ref.fFocus != null) {
                ref.fFocus.set(null, zonePoleNpc);
            }

            if (ref.clsGlobalSvc != null && ref.mGetSvc != null) {
                Object svc = invokeMethod(ref.mGetSvc, null);
                if (svc != null) {
                    // Tìm hàm đổi khu trong GlobalService (nhận 1 tham số byte zoneId)
                    for (Method m : ref.clsGlobalSvc.getDeclaredMethods()) {
                        if (Modifier.isStatic(m.getModifiers()) || m.getReturnType() != void.class) continue;
                        Class<?>[] pt = m.getParameterTypes();
                        String mn = m.getName().toLowerCase();
                        if (pt.length == 1 && pt[0] == byte.class
                                && (mn.contains("area") || mn.contains("zone") || mn.equals("n0023") || mn.equals("n0024"))) {
                            m.setAccessible(true);
                            invokeMethod(m, svc, (byte) targetZoneId);
                            cachedChangeZoneMethod = m;
                            break;
                        }
                    }
                }
            }

            // Kết hợp thao tác chọn Khu nếu bảng Khu đang mở trên màn hình
            if (zonePoleNpc != null) {
                int px = ref.fX.getInt(player);
                int py = ref.fY.getInt(player);
                int nx = ref.fX.getInt(zonePoleNpc);
                int ny = ref.fY.getInt(zonePoleNpc);
                if (Math.hypot(nx - px, ny - py) <= 72) {
                    tapKeyHelper(-5, 30, 80);
                    for (int i = 0; i < targetZoneId && i < 15; i++) {
                        tapKeyHelper(-2, 25, 45);
                    }
                    tapKeyHelper(-5, 30, 60);
                }
            }

            currentZoneId = targetZoneId;
            lastZoneSwitchTime = now;
            waitingForZoneListResponse = false;
            System.err.println("[AUTO-ZONE] (" + reason + ") \u0110\u00e3 chuy\u1ec3n sang Khu " + (targetZoneId + 1)
                    + " t\u1ea1i Map " + currentMapId + "!");
            dirty = true;
            return true;
        } catch (Throwable t) {
            logReflectErrorOnce("performZoneSwitch", t);
            return false;
        }
    }

    /**
     * Kiểm tra mật độ người chơi tại bãi hiện tại và tự động đổi sang khu vắng nhất nếu bị tranh bãi.
     * Trả về true nếu vừa thực hiện thao tác đổi khu trong tick này.
     */
    private static boolean checkAndAutoSwitchZoneIfCrowded(
            RefCache ref, Object player, int currentMapId, int spotX, int spotY, boolean isBossFight) {
        long now = System.currentTimeMillis();

        // Xử lý yêu cầu đổi khu thủ công từ lệnh IPC AUTO_ZONE:goto:<khu>
        if (manualTargetZoneRequest >= 0) {
            int reqZone = manualTargetZoneRequest;
            manualTargetZoneRequest = -1;
            return performZoneSwitch(ref, player, currentMapId, reqZone, "L\u1ec7nh \u0111\u1ed5i khu th\u1ee7 c\u00f4ng");
        }

        if (!autoZoneEnabled || isBossFight) return false;
        // Không tự động đổi khu ở các Map Làng / Thị trấn hoặc Map < 2
        if (currentMapId <= 1 || currentMapId == 19 || currentMapId == 33 || currentMapId == 67 || currentMapId == 92) {
            spotCrowdedSinceTime = 0L;
            return false;
        }
        // Không đổi khu ở các phòng Boss cuối nhánh
        if (getBranchBossRoom(currentMapId) == currentMapId) {
            spotCrowdedSinceTime = 0L;
            return false;
        }

        int curZone = readCurrentZoneId(ref);
        int[] playerCounts = countOtherPlayersAroundSpot(ref, player, spotX, spotY);
        int playersInSpot = playerCounts[0];

        if (playersInSpot < autoZoneMaxPlayersInSpot) {
            spotCrowdedSinceTime = 0L;
            return false;
        }

        // Bắt đầu bấm giờ từ lúc phát hiện có người đứng chung bãi
        if (spotCrowdedSinceTime == 0L) {
            spotCrowdedSinceTime = now;
            return false;
        }

        // Chờ đủ 4s (CROWDED_CONFIRM_MS) để tránh đổi khu nhầm khi có người chỉ chạy ngang qua bãi
        if (now - spotCrowdedSinceTime < CROWDED_CONFIRM_MS) {
            return false;
        }
        if (now - lastZoneSwitchTime < ZONE_SWITCH_COOLDOWN_MS) {
            return false;
        }

        // Đánh dấu khu hiện tại đang đông người (TTL 3 phút)
        visitedCrowdedZones.put(curZone, now + 180000L);
        spotCrowdedSinceTime = 0L;
        lockedTargetMob = null;

        int bestZone = selectLeastCrowdedZone(ref, curZone);
        byte[] zonePop = readZonePopulationArray(ref);
        String popDetail = (zonePop != null && bestZone >= 0 && bestZone < zonePop.length)
                ? ("s\u1ed1 ng\u01b0\u1eddi=" + zonePop[bestZone])
                : "khu \u01b0u ti\u00ean";

        String reason = "Ph\u00e1t hi\u1ec7n " + playersInSpot + " ng\u01b0\u1eddi tranh b\u00e3i t\u1ea1i Khu "
                + (curZone + 1) + " -> Chuy\u1ec3n sang Khu " + (bestZone + 1) + " (" + popDetail + ")";
        return performZoneSwitch(ref, player, currentMapId, bestZone, reason);
    }

    /**
     * Xuất thông tin chẩn đoán danh sách khu và số lượng người chơi ra log
     */
    private static void dumpAutoZoneDiagnostics() {
        synchronized (TICK_LOCK) {
            try {
                ClassLoader cl = getGameClassLoader();
                if (cl == null) return;
                RefCache ref = getRef(cl);
                Object player = (ref != null && ref.fPlayer != null) ? ref.fPlayer.get(null) : null;
                int mapId = getCurrentMapId(ref);
                int curZone = readCurrentZoneId(ref);
                int[] counts = countOtherPlayersAroundSpot(ref, player, lockedSpotX, lockedSpotY);
                byte[] zonePop = readZonePopulationArray(ref);

                System.err.println("==================== [AUTO_ZONE:diag] ====================");
                System.err.println("B\u1eadt Auto \u0110\u1ed5i Khu=" + autoZoneEnabled
                        + " | Map=" + mapId
                        + " | Khu hi\u1ec7n t\u1ea1i=" + (curZone + 1) + " (index=" + curZone + ")"
                        + " | Ng\u01b0\u1ee1ng tranh b\u00e3i=" + autoZoneMaxPlayersInSpot + " ng\u01b0\u1eddi");
                System.err.println("Ng\u01b0\u1eddi ch\u01a1i kh\u00e1c trong b\u00e3i (" + lockedSpotX + "," + lockedSpotY + ")="
                        + counts[0] + " | T\u1ed5ng ng\u01b0\u1eddi ch\u01a1i kh\u00e1c tr\u00ean Map=" + counts[1]);
                if (zonePop != null) {
                    StringBuilder sb = new StringBuilder("Danh s\u00e1ch Khu t\u1eeb Server (t\u1ed5ng=" + zonePop.length + " khu): ");
                    for (int i = 0; i < zonePop.length; i++) {
                        sb.append("[Khu ").append(i + 1).append(": ").append(zonePop[i]).append("] ");
                    }
                    System.err.println(sb.toString());
                } else {
                    System.err.println("Danh s\u00e1ch Khu: Ch\u01b0a n\u1ea1p m\u1ea3ng byte[] t\u1eeb Server (s\u1ebd d\u00f9ng ch\u1ebf \u0111\u1ed9 xoay v\u00f2ng Khu th\u00f4ng minh).");
                }
                System.err.println("C\u00e1c khu \u0111\u00f4ng v\u1eeba n\u00e9: " + visitedCrowdedZones.keySet());
                System.err.println("==========================================================");
            } catch (Throwable t) {
                System.err.println("[AUTO-ZONE-DIAG] L\u1ed7i: " + t.getMessage());
            }
        }
    }
    // =========================================================================
    // P1-7 & P1-C: TU DONG DANH QUAI THEO LV (+-2 LV, UU TIEN -2 LV, NE <= -3 LV)
    // =========================================================================
    private static void executeAutoLevelFarming(
            RefCache ref, Object player, int currentMapId, int mobCountOnMap,
            int playerLevel, String reason) {
        try {
            int liveLv = getPlayerLevel(ref, player);
            if (liveLv >= 1) playerLevel = liveLv;

            int[] spotInfo = getFarmMapAndCoordsForLevel(ref, playerLevel);
            int farmMapId = spotInfo[0];
            int spotX = spotInfo[1];
            int spotY = spotInfo[2];
            int targetMobLv = spotInfo.length >= 4 ? spotInfo[3] : playerLevel;

            currentAqStateLabel = maxLevelCompletedSteadyMode ? "COMPLETED_STEADY" : "LEVEL_FARMING";
            currentAqDetailLabel = reason + " [C\u1eafm b\u00e3i Map " + farmMapId
                    + " (" + spotX + "," + spotY + ") | Lv." + playerLevel + "]";

            if (trackedQuestId != -999 || trackedTargetMobId != farmMapId) {
                trackedQuestId = -999;
                trackedTargetMobId = farmMapId;
                lockedTargetMob = null;
                resetLockedMonsterSpot();
            }

            if (currentMapId != farmMapId) {
                lockedTargetMob = null;
                triggerAutoRouteToQuest(ref, null, player, farmMapId, mobCountOnMap);
                return;
            }

            if (System.currentTimeMillis() - currentMapEnterTime < 300L) return;

            int px = ref.fX.getInt(player);
            int py = ref.fY.getInt(player);
            updatePositionProgress(px, py);

            long now = System.currentTimeMillis();
            int distPlayerToSpot = (int) Math.hypot(spotX - px, spotY - py);

            // 1. Luôn đưa nhân vật về đứng đúng tại tâm bãi (spotX, spotY) với sai số <= 36px
            if (distPlayerToSpot > 36) {
                lockedTargetMob = null;
                if (now - lastRouteTime > 2000L || currentRoutingTargetMap != currentMapId
                        || Math.hypot(spotX - currentRoutingTargetX, spotY - currentRoutingTargetY) > 20) {
                    lastRouteTime = now;
                    currentRoutingTargetMap = currentMapId;
                    currentRoutingTargetX = spotX;
                    currentRoutingTargetY = spotY;
                    startNavigatorRouteRequest(ref, currentMapId, true, spotX, spotY, 18, false,
                            "\u0110i v\u00e0o neo t\u1ea1i t\u00e2m b\u00e3i (" + spotX + "," + spotY + ")");
                } else {
                    tickNavigator(ref);
                }
                if (now - lastPositionChangeTime > 1500L) {
                    stepDirectionalKeyTowards(px, py, spotX, spotY, now - lastPositionChangeTime > 2800L);
                } else {
                    releaseAllDirectionalKeys();
                }
                return;
            }

            // 2. Đã đứng tại tâm bãi (distPlayerToSpot <= 36px) -> Ngắt hoàn toàn bộ tìm đường để ĐỨNG YÊN TẠI BÃI
            if (currentRoutingTargetMap >= 0) {
                stopNavOnly(ref);
                currentRoutingTargetMap = -1;
            }
            releaseAllDirectionalKeys();
            lastPositionChangeTime = now;
            if (checkAndAutoSwitchZoneIfCrowded(ref, player, currentMapId, spotX, spotY, false)) {
                return;
            }

            Object vecActors = (ref.fVecActors != null) ? ref.fVecActors.get(null) : null;
            if (vecActors == null) return;
            Method[] vm = getVectorMethods(vecActors);
            if (vm == null) return;

            Object bestMob = null;
            int bestDistToPlayer = Integer.MAX_VALUE;

            if (lockedTargetMob != null && ref.clsMonsterBase.isInstance(lockedTargetMob)) {
                try {
                    if (!ref.fIsDead.getBoolean(lockedTargetMob)
                            && ref.fState.getInt(lockedTargetMob) != 4
                            && ref.fHp.getInt(lockedTargetMob) > 0) {
                        int mx = ref.fX.getInt(lockedTargetMob);
                        int my = ref.fY.getInt(lockedTargetMob);
                        if (Math.hypot(mx - spotX, my - spotY) <= FARM_SPOT_RADIUS) {
                            bestMob = lockedTargetMob;
                            bestDistToPlayer = (int) Math.hypot(mx - px, my - py);
                        } else {
                            lockedTargetMob = null;
                        }
                    } else {
                        lockedTargetMob = null;
                    }
                } catch (Throwable ignored) {
                    lockedTargetMob = null;
                }
            }

            int count = (Integer) invokeMethod(vm[0], vecActors);
            if (bestMob == null) {
                for (int i = 0; i < count; i++) {
                    Object obj = invokeMethod(vm[1], vecActors, i);
                    if (obj == null || obj == player || temporarilyIgnoredMobs.containsKey(obj)) continue;

                    byte objType = ref.fObjType.getByte(obj);
                    if (!ref.clsMonsterBase.isInstance(obj) && objType != 1) continue;
                    if (ref.fIsDead.getBoolean(obj) || ref.fState.getInt(obj) == 4) continue;
                    if (ref.fHp.getInt(obj) <= 0) continue;

                    if (ref.clsMonsterBase.isInstance(obj)) {
                        int mobTemplateId = ref.fMobTemplateId.getInt(obj);
                        if (mobTemplateId == 0 && currentMapId > 0) continue;
                    }

                    int mx = ref.fX.getInt(obj);
                    int my = ref.fY.getInt(obj);
                    if (Math.hypot(mx - spotX, my - spotY) > FARM_SPOT_RADIUS) continue;

                    int distToPlayer = (int) Math.hypot(mx - px, my - py);
                    if (distToPlayer < bestDistToPlayer) {
                        bestDistToPlayer = distToPlayer;
                        bestMob = obj;
                    }
                }
                lockedTargetMob = bestMob;
            }

            // 3. Đứng tại tâm bãi xả kỹ năng vào quái (KHÔNG gọi movePlayerCloseToTarget để tránh chạy khỏi bãi)
            if (bestMob != null) {
                ref.fFocus.set(null, bestMob);
                performFullSkillCombo(ref, bestMob);

                String mName = (String) ref.fObjName.get(bestMob);
                logRateLimited("COMBAT_FARM", 5000L,
                        "[AUTO-LEVEL] \u0110\u1ee9ng c\u1eafm ch\u1ed1t (" + spotX + "," + spotY
                                + ") -> \u0110ang \u0111\u00e1nh " + (mName != null ? mName : "Qu\u00e1i")
                                + " (Lv." + targetMobLv + ")");
                dirty = true;
            }
        } catch (Throwable e) {
            logReflectErrorOnce("executeAutoLevelFarming", e);
        }
    }

    // =========================================================================
    // HE THONG TU DONG BAN VAT PHAM KHI HANH TRANG DAY (MENU SETUP BAN DO)
    // Ho tro loc: Mau (Trang, Xanh, Vang, Tim, Cam), Do Khoa / Khong Khoa, Do Da Cuong Hoa
    // =========================================================================
    private static final class BagItemEntry {
        final Object rawItem;
        final int slotIndex;
        final int itemId;
        final byte category;      // 3 = Trang bi (Equipment), 4 = Thuoc, 5 = Nhiem vu, 7 = Nguyen lieu
        final int color;          // 0=Trang, 1=Xanh, 2=Vang, 3=Tim, 4=Cam
        final int enhanceLevel;   // Cap cuong hoa (+0 .. +15)
        final boolean isLocked;   // true = Do khoa, false = Do khong khoa
        final String name;

        BagItemEntry(Object rawItem, int slotIndex, int itemId, byte category,
                     int color, int enhanceLevel, boolean isLocked, String name) {
            this.rawItem = rawItem;
            this.slotIndex = slotIndex;
            this.itemId = itemId;
            this.category = category;
            this.color = Math.max(0, Math.min(4, color));
            this.enhanceLevel = Math.max(0, enhanceLevel);
            this.isLocked = isLocked;
            this.name = name != null ? name : "";
        }
    }

    /**
     * Tu dong tim Vector Hanh trang (vecInventory) cua nhan vat qua Reflection
     */
    private static Object resolvePlayerInventoryVector(RefCache ref, Object player) {
        if (ref == null) return null;
        try {
            if (cachedVecInventoryField != null) {
                Object v = Modifier.isStatic(cachedVecInventoryField.getModifiers())
                        ? cachedVecInventoryField.get(null)
                        : (player != null ? cachedVecInventoryField.get(player) : null);
                if (v != null && getVectorMethods(v) != null) return v;
            }

            // Quet tren class Player (n0001.n0133) va cac lop lien quan de tim mVector chua Item hanh trang
            List<Class<?>> candidateClasses = new ArrayList<>();
            if (ref.clsPlayer != null) candidateClasses.add(ref.clsPlayer);
            if (ref.clsGameScr != null) candidateClasses.add(ref.clsGameScr);
            ClassLoader cl = ref.cl;
            for (String cName : new String[]{"n0001.n0060", "n0001.n0085", "n0001.n0086", "n0001.n0098"}) {
                try {
                    Class<?> c = Class.forName(cName, false, cl);
                    if (c != null) candidateClasses.add(c);
                } catch (Throwable ignored) {}
            }

            Object bestVec = null;
            Field bestField = null;
            int bestScore = -1;

            for (Class<?> cls : candidateClasses) {
                Class<?> cur = cls;
                while (cur != null && cur != Object.class) {
                    for (Field f : cur.getDeclaredFields()) {
                        Class<?> ft = f.getType();
                        if (ft.isPrimitive() || ft == String.class || ft.isArray()) continue;
                        if (ref.clsVector != null && !ref.clsVector.isAssignableFrom(ft)) continue;
                        if (!ft.getName().startsWith("n0001.")) continue;
                        try {
                            f.setAccessible(true);
                            boolean isStatic = Modifier.isStatic(f.getModifiers());
                            if (!isStatic && (player == null || !cur.isInstance(player))) continue;
                            Object val = isStatic ? f.get(null) : f.get(player);
                            if (val == null) continue;
                            if (val == (ref.fVecActors != null ? ref.fVecActors.get(null) : null)) continue;
                            if (val == (ref.fVecNew != null ? ref.fVecNew.get(null) : null)) continue;
                            if (val == (ref.fVecFinish != null ? ref.fVecFinish.get(null) : null)) continue;
                            if (val == (ref.fVecDoingMain != null ? ref.fVecDoingMain.get(null) : null)) continue;
                            if (val == (ref.fVecDoingSub != null ? ref.fVecDoingSub.get(null) : null)) continue;

                            Method[] vm = getVectorMethods(val);
                            if (vm == null) continue;
                            int sz = (Integer) invokeMethod(vm[0], val);
                            if (sz <= 0 || sz > 200) continue;

                            Object firstElem = invokeMethod(vm[1], val, 0);
                            if (firstElem == null) continue;
                            if (ref.clsQuest != null && ref.clsQuest.isInstance(firstElem)) continue;
                            if (ref.clsMainObj != null && ref.clsMainObj.isInstance(firstElem)) continue;

                            // Kiem tra cau truc cua phan tu co phai la Vat pham (Item) hay khong
                            int itemScore = scoreObjectAsInventoryItem(firstElem);
                            if (itemScore > 0) {
                                int totalScore = itemScore * 10 + Math.min(sz, 50);
                                if (totalScore > bestScore) {
                                    bestScore = totalScore;
                                    bestVec = val;
                                    bestField = f;
                                }
                            }
                        } catch (Throwable ignored) {}
                    }
                    cur = cur.getSuperclass();
                }
            }

            if (bestField != null) {
                cachedVecInventoryField = bestField;
            }
            return bestVec;
        } catch (Throwable ignored) {}
        return null;
    }

    private static int scoreObjectAsInventoryItem(Object obj) {
        if (obj == null) return 0;
        int stringFields = 0;
        int byteFields = 0;
        int shortOrIntFields = 0;
        Class<?> cur = obj.getClass();
        while (cur != null && cur.getName().startsWith("n0001.")) {
            for (Field f : cur.getDeclaredFields()) {
                if (Modifier.isStatic(f.getModifiers())) continue;
                Class<?> t = f.getType();
                if (t == String.class) stringFields++;
                else if (t == byte.class) byteFields++;
                else if (t == short.class || t == int.class) shortOrIntFields++;
            }
            cur = cur.getSuperclass();
        }
        return (stringFields >= 1 && byteFields >= 2 && shortOrIntFields >= 2) ? (stringFields + byteFields) : 0;
    }

    /**
     * Doc so o hanh trang toi da (maxInventory) cua nhan vat
     */
    private static int getPlayerMaxBagCapacity(RefCache ref, Object player) {
        if (cachedMaxInventoryField != null) {
            try {
                Object target = Modifier.isStatic(cachedMaxInventoryField.getModifiers()) ? null : player;
                int v = ((Number) cachedMaxInventoryField.get(target)).intValue();
                if (v >= 20 && v <= 160) {
                    lastObservedMaxBag = v;
                    return v;
                }
            } catch (Throwable ignored) {}
        }
        if (ref != null && ref.clsPlayer != null) {
            try {
                for (Field f : ref.clsPlayer.getDeclaredFields()) {
                    Class<?> t = f.getType();
                    if (t != int.class && t != short.class && t != byte.class) continue;
                    f.setAccessible(true);
                    Object target = Modifier.isStatic(f.getModifiers()) ? null : player;
                    if (target == null && !Modifier.isStatic(f.getModifiers())) continue;
                    int val = ((Number) f.get(target)).intValue();
                    if (val == 42 || val == 48 || val == 54 || val == 60 || val == 66 || val == 72 || val == 36) {
                        cachedMaxInventoryField = f;
                        lastObservedMaxBag = val;
                        return val;
                    }
                }
            } catch (Throwable ignored) {}
        }
        return lastObservedMaxBag;
    }

    /**
     * Phan tich chi tiet tung vat pham trong hanh trang (Ten, ID, Loai, Mau 0..4, Cuong hoa +X, Khoa/Khong khoa)
     */
    private static BagItemEntry parseBagItemEntry(RefCache ref, Object itemObj, int slotIdx) {
        if (itemObj == null) return null;
        String itemName = "";
        String extraInfoText = "";
        int itemId = slotIdx;
        byte category = 3; // Mac dinh 3 = Trang bi
        int color = 0;     // 0 = Trang
        int enhanceLv = 0;
        boolean isLocked = false;

        try {
            List<Field> allFields = new ArrayList<>();
            Class<?> cur = itemObj.getClass();
            while (cur != null && cur.getName().startsWith("n0001.")) {
                allFields.addAll(Arrays.asList(cur.getDeclaredFields()));
                cur = cur.getSuperclass();
            }

            List<Integer> smallBytes = new ArrayList<>();
            for (Field f : allFields) {
                if (Modifier.isStatic(f.getModifiers())) continue;
                f.setAccessible(true);
                Class<?> ft = f.getType();
                Object val = f.get(itemObj);
                if (val == null) continue;

                if (ft == String.class) {
                    String s = ((String) val).trim();
                    if (!s.isEmpty()) {
                        if (itemName.isEmpty() || (s.length() > 2 && itemName.matches("^\\d+$"))) {
                            itemName = s;
                        } else {
                            extraInfoText += " " + s;
                        }
                    }
                } else if (ft == String[].class) {
                    for (String s : (String[]) val) {
                        if (s != null) extraInfoText += " " + s;
                    }
                } else if (ft == short.class) {
                    int sv = ((Short) val).intValue();
                    if (sv >= 0 && sv < 30000 && itemId == slotIdx) {
                        itemId = sv;
                    }
                } else if (ft == byte.class) {
                    byte bv = (Byte) val;
                    smallBytes.add((int) bv);
                } else if (ft == boolean.class) {
                    if ((Boolean) val) {
                        // Kiem tra ten field goi y khoa neu co
                        String fn = f.getName().toLowerCase();
                        if (fn.contains("lock")) isLocked = true;
                    }
                }
            }

            // Phan tich cap cuong hoa (+1 .. +15) tu ten vat pham hoac thong tin phu
            Matcher mPlus = Pattern.compile("\\+\\s*(\\d{1,2})").matcher(itemName + " " + extraInfoText);
            if (mPlus.find()) {
                try {
                    int parsedPlus = Integer.parseInt(mPlus.group(1));
                    if (parsedPlus >= 1 && parsedPlus <= 25) {
                        enhanceLv = parsedPlus;
                    }
                } catch (Throwable ignored) {}
            }

            String normName = normalizeText(ref, itemName);
            String normAll = normalizeText(ref, itemName + " " + extraInfoText);

            // Nhan dien loai vat pham (Khong bao gio ban thuoc HP/MP, vat pham nhiem vu, nguyen lieu/da/ngoc)
            if (normName.contains("binhmau") || normName.contains("binhnangluong")
                    || normName.contains("hp") || normName.contains("mp")
                    || normName.contains("duocpham") || normName.contains("thuoc")) {
                category = 4; // Thuoc
            } else if (normName.contains("nhiemvu") || normName.contains("ngoc")
                    || normName.contains("da") || normName.contains("longvu")
                    || normName.contains("ve") || normName.contains("nguyenlieu")
                    || normName.contains("chinhphuc") || normName.contains("tinhtu")) {
                category = 7; // Nguyen lieu / Vat pham dac biet (Bao ve)
            } else {
                // Kiem tra cac byte truong chuan cua HSO Item:
                // Trong HSO: ItemCatagory = 3 (Trang bi), colorName = 0..4 (Trang, Xanh, Vang, Tim, Cam), tier = 0..15, isLock = 0..1
                for (int b : smallBytes) {
                    if (b == 4 || b == 5 || b == 7) {
                        // Neu ten khong phai trang bi ro rang ma co category = 4/5/7
                        if (!isLikelyEquipmentName(normName)) {
                            category = (byte) b;
                        }
                    }
                }
                for (int b : smallBytes) {
                    if (b >= 0 && b <= 4) {
                        color = b;
                        break;
                    }
                }
                if (enhanceLv == 0) {
                    for (int b : smallBytes) {
                        if (b >= 1 && b <= 15 && b != 3 && b != color) {
                            // Neu co truong tier > 0
                            enhanceLv = b;
                            break;
                        }
                    }
                }
            }

            // Nhan dien trang thai Khoa / Khong khoa tu mo ta va byte isLock
            if (normAll.contains("dakhoa") || normAll.contains("vatphamkhoa")
                    || normAll.contains("khongthegiaodich") || normAll.contains("(khoa)")) {
                isLocked = true;
            } else if (normAll.contains("khongkhoa") || normAll.contains("chuakhoa")) {
                isLocked = false;
            }
        } catch (Throwable ignored) {}

        return new BagItemEntry(itemObj, slotIdx, itemId, category, color, enhanceLv, isLocked, itemName);
    }

    private static boolean isLikelyEquipmentName(String normName) {
        if (normName == null || normName.isEmpty()) return false;
        String[] eqKeywords = new String[]{
                "kiem", "dao", "gay", "cung", "no", "sung", "bua", "riu", "thuong", "kich",
                "ao", "quan", "mu", "non", "gang", "giay", "nhan", "daychuyen", "meday", "canh", "khien", "giap"
        };
        for (String kw : eqKeywords) {
            if (normName.contains(kw)) return true;
        }
        return false;
    }

    /**
     * Kiem tra mot vat pham trong hanh trang co khop dieu kien BAN cua Menu Setup hay khong:
     * 1. Phai la Trang bi (category == 3)
     * 2. Mau sac (Trang=0, Xanh=1, Vang=2, Tim=3, Cam=4) duoc tich chon trong autoSellColorFlags
     * 3. Trang thai Khoa (autoSellLocked) / Khong khoa (autoSellUnlocked) khop cau hinh
     * 4. Trang thai Da cuong hoa (autoSellEnhanced) / Chua cuong hoa (autoSellUnenhanced) khop cau hinh
     */
    private static boolean shouldSellBagItem(BagItemEntry item) {
        if (item == null || item.category != 3) return false; // Chi ban Trang bi, bao ve tuyet doi thuoc/nguyen lieu/nhiem vu
        if (item.color < 0 || item.color >= autoSellColorFlags.length) return false;

        // 1. Kiem tra Mau sac (Trang / Xanh / Vang / Tim / Cam)
        if (!autoSellColorFlags[item.color]) return false;

        // 2. Kiem tra Do Khoa vs Do Khong Khoa
        if (item.isLocked && !autoSellLocked) return false;
        if (!item.isLocked && !autoSellUnlocked) return false;

        // 3. Kiem tra Do Da Cuong Hoa (+1 tro len) vs Chua Cuong Hoa (+0)
        if (item.enhanceLevel > 0 && !autoSellEnhanced) return false;
        if (item.enhanceLevel == 0 && !autoSellUnenhanced) return false;

        return true;
    }

    /**
     * Gui goi tin ban vat pham qua GlobalService (n0001.n0044) va xac nhan hop thoai
     */
    private static boolean sendSellItemPacket(RefCache ref, BagItemEntry item) {
        if (ref == null || item == null || ref.clsGlobalSvc == null || ref.mGetSvc == null) return false;
        try {
            Object svc = invokeMethod(ref.mGetSvc, null);
            if (svc == null) return false;

            boolean invoked = false;
            // Tim cac ham thao tac ban/xoa vat pham trong GlobalService (n0001.n0044):
            // Chuan HSO: delete_Item_In_Bag / sell_Item co chu ky (byte cat, short id, byte type) hoac (short id, byte cat, byte type)
            for (Method m : ref.clsGlobalSvc.getDeclaredMethods()) {
                if (Modifier.isStatic(m.getModifiers()) || m.getReturnType() != void.class) continue;
                Class<?>[] pt = m.getParameterTypes();
                if (pt.length == 3 && pt[0] == byte.class && pt[1] == short.class && pt[2] == byte.class) {
                    m.setAccessible(true);
                    invokeMethod(m, svc, item.category, (short) item.itemId, (byte) 0);
                    invoked = true;
                } else if (pt.length == 3 && pt[0] == byte.class && pt[1] == int.class && pt[2] == byte.class) {
                    m.setAccessible(true);
                    invokeMethod(m, svc, item.category, item.itemId, (byte) 0);
                    invoked = true;
                }
            }

            // Xac nhan hop thoai "Ban co muon ban..." neu hien len
            tapKeyHelper(-6, 25, 45);
            tapKeyHelper(-5, 25, 45);
            dirty = true;
            return invoked;
        } catch (Throwable t) {
            logReflectErrorOnce("sendSellItemPacket", t);
            return false;
        }
    }

    /**
     * Quet hanh trang, kiem tra neu day (hoac co lenh Ban Ngay tu Menu Setup) thi tu dong ban cac trang bi khop bo loc
     * @return true neu dang ban di chuyen ve NPC de ban do (tam nhuong tick hien tai cho viec ban do)
     */
    private static boolean checkAndExecuteAutoSell(RefCache ref, Object player, int currentMapId) {
        long now = System.currentTimeMillis();

        // 1. Neu Mod dang giu khoa n0208 (vi du Auto Ban Do co san trong Mod dang chay ve lang ban do)
        if (isExternalModServiceHoldingLock(ref)) {
            if (!isAutoSellingSession) {
                isAutoSellingSession = true;
                modAutoSellYieldStartTime = now;
                lockedTargetMob = null;
                autoQuestRouteToken = null;
                currentRoutingTargetMap = -1;
            }
            lastPositionChangeTime = now;
            currentAqStateLabel = "MOD_AUTO_SELL";
            currentAqDetailLabel = "Nh\u01b0\u1eddng Mod t\u1ef1 \u0111\u1ed9ng v\u1ec1 l\u00e0ng b\u00e1n \u0111\u1ed3";
            logRateLimited("MOD_SELL_ACTIVE", 5000L,
                    "[AUTO-SELL] Ch\u1ee9c n\u0103ng T\u1ef1 \u0110\u1ed9ng B\u00e1n \u0110\u1ed3 trong Mod \u0111ang gi\u1eef quy\u1ec1n \u0111i\u1ec1u khi\u1ec3n (Map " + currentMapId + ") -> T\u1ea1m d\u1eebng AUTO_QUEST \u0111\u1ec3 Mod b\u00e1n xong.");
            return true;
        }

        boolean forceNow = autoSellNowRequested;
        if (!autoSellEnabled && !forceNow && !isAutoSellingSession) return false;

        try {
            Object vecBag = resolvePlayerInventoryVector(ref, player);
            int bagCount = lastObservedBagCount;
            int maxBag = getPlayerMaxBagCapacity(ref, player);

            if (vecBag != null) {
                Method[] vm = getVectorMethods(vecBag);
                if (vm != null) {
                    bagCount = (Integer) invokeMethod(vm[0], vecBag);
                    lastObservedBagCount = bagCount;
                }
            }

            int effectiveThreshold = (autoSellBagThreshold > 0)
                    ? Math.min(autoSellBagThreshold, Math.max(1, maxBag - 1))
                    : Math.max(1, maxBag - 2);

            // 2. Neu dang trong phien nhuong quyen cho Mod di ban do
            if (isAutoSellingSession) {
                lastPositionChangeTime = now;
                boolean inTown = (currentMapId == 1 || currentMapId == 19 || currentMapId == 33 || currentMapId == 67);
                if (bagCount < bagCountAtYieldStart) {
                    // So o tui dang giam (Mod dang ban do) -> Gia han thoi gian cho them 4s
                    totalAutoSoldItemsCount += (bagCountAtYieldStart - bagCount);
                    bagCountAtYieldStart = bagCount;
                    modAutoSellYieldStartTime = now;
                }
                long elapsedYield = now - modAutoSellYieldStartTime;
                long maxWaitMs = inTown ? 12000L : 8000L;
                if (elapsedYield < maxWaitMs) {
                    currentAqStateLabel = "MOD_AUTO_SELL";
                    currentAqDetailLabel = "\u0110ang ch\u1edd Mod v\u1ec1 b\u00e1n \u0111\u1ed3 (" + bagCount + "/" + maxBag + " \u00f4)";
                    return true;
                }
                // Het thoi gian cho hoac Mod da ban xong va nha khoa -> Tiep tuc AUTO_QUEST
                isAutoSellingSession = false;
                autoSellNowRequested = false;
                inventoryFullDialogDetected = false;
                lastAutoSellYieldCooldownUntil = now + 30000L;
                lastSeenBagCountForYield = bagCount;
                System.err.println("[AUTO-SELL] Ho\u00e0n t\u1ea5t nh\u01b0\u1eddng Mod b\u00e1n \u0111\u1ed3 (H\u00e0nh trang hi\u1ec7n t\u1ea1i: " + bagCount + "/" + maxBag + " \u00f4) -> Ti\u1ebfp t\u1ee5c AUTO_QUEST!");
                return false;
            }

            // 3. Kiem tra khi day hanh trang (hoac vua nhat them do khi tui da >= 20 o) -> Nha khoa n0208 giong het thao tac tat AUTO_QUEST de Mod kich hoat ve ban do
            boolean bagIncreased = (lastSeenBagCountForYield >= 0 && bagCount > lastSeenBagCountForYield);
            lastSeenBagCountForYield = bagCount;

            boolean shouldYieldToModSell = forceNow
                    || inventoryFullDialogDetected
                    || ((bagCount >= effectiveThreshold) && (now >= lastAutoSellYieldCooldownUntil || bagIncreased))
                    || (bagIncreased && bagCount >= 24 && now >= lastAutoSellYieldCooldownUntil);

            if (shouldYieldToModSell) {
                isAutoSellingSession = true;
                autoSellNowRequested = false;
                modAutoSellYieldStartTime = now;
                bagCountAtYieldStart = bagCount;
                lockedTargetMob = null;
                releaseAllDirectionalKeys();
                releaseAutoRouteToken(); // Nha hoan toan khoa n0207 va lo trinh n0193 de Mod tu dong ve lang ban do
                lastPositionChangeTime = now;
                currentAqStateLabel = "MOD_AUTO_SELL";
                currentAqDetailLabel = "Nh\u1ea3 kh\u00f3a \u0111\u1ec3 Mod v\u1ec1 b\u00e1n \u0111\u1ed3 (" + bagCount + "/" + maxBag + " \u00f4)";
                System.err.println("[AUTO-SELL] H\u00e0nh trang " + bagCount + "/" + maxBag
                        + " \u00f4 -> \u0110\u00e3 nh\u1ea3 kh\u00f3a AUTO_QUEST \u0111\u1ec3 ch\u1ee9c n\u0103ng T\u1ef1 \u0110\u1ed9ng B\u00e1n \u0110\u1ed3 trong Mod v\u1ec1 l\u00e0ng b\u00e1n \u0111\u1ed3!");
                return true;
            }
        } catch (Throwable t) {
            logReflectErrorOnce("checkAndExecuteAutoSell", t);
            isAutoSellingSession = false;
        }
        return false;
    }

    /**
     * In bang chan doan toan bo vat pham trong Hanh trang va trang thai khop bo loc Menu Setup
     */
    private static void dumpInventoryAutoSellDiagnostics() {
        synchronized (TICK_LOCK) {
            try {
                ClassLoader cl = getGameClassLoader();
                if (cl == null) return;
                RefCache ref = getRef(cl);
                Object player = (ref != null && ref.fPlayer != null) ? ref.fPlayer.get(null) : null;
                Object vecBag = resolvePlayerInventoryVector(ref, player);
                int maxBag = getPlayerMaxBagCapacity(ref, player);

                System.err.println("==================== [AUTO_SELL:diag] ====================");
                System.err.println("C\u1ea5u h\u00ecnh Menu Setup B\u00e1n \u0110\u1ed3: B\u1eadt=" + autoSellEnabled
                        + " | Ng\u01b0\u1ee1ng \u0111\u1ea7y=" + autoSellBagThreshold + "/" + maxBag
                        + " | M\u00e0u=[Tr\u1eafng:" + autoSellColorFlags[0]
                        + ", Xanh:" + autoSellColorFlags[1]
                        + ", V\u00e0ng:" + autoSellColorFlags[2]
                        + ", T\u00edm:" + autoSellColorFlags[3]
                        + ", Cam:" + autoSellColorFlags[4] + "]"
                        + " | \u0110\u1ed3 Kh\u00f3a=" + autoSellLocked
                        + " | Kh\u00f4ng Kh\u00f3a=" + autoSellUnlocked
                        + " | \u0110\u00e3 C\u01b0\u1eddng H\u00f3a=" + autoSellEnhanced);

                if (vecBag == null) {
                    System.err.println("H\u00e0nh trang: Ch\u01b0a t\u00ecm th\u1ea5y vector h\u00e0nh trang (H\u00e3y m\u1edf t\u00fai \u0111\u1ed3 trong game 1 l\u1ea7n).");
                } else {
                    Method[] vm = getVectorMethods(vecBag);
                    int sz = (vm != null) ? (Integer) invokeMethod(vm[0], vecBag) : 0;
                    System.err.println("Danh s\u00e1ch v\u1eadt ph\u1ea9m trong H\u00e0nh trang (" + sz + "/" + maxBag + " \u00f4):");
                    for (int i = 0; i < sz; i++) {
                        Object raw = invokeMethod(vm[1], vecBag, i);
                        BagItemEntry e = parseBagItemEntry(ref, raw, i);
                        if (e == null) continue;
                        boolean match = shouldSellBagItem(e);
                        System.err.println("  Slot #" + i + ": ID=" + e.itemId
                                + " | T\u00ean='" + e.name + "'"
                                + " | Lo\u1ea1i=" + e.category
                                + " | M\u00e0u=" + ITEM_COLOR_NAMES[e.color] + "(" + e.color + ")"
                                + " | C\u01b0\u1eddng h\u00f3a=+" + e.enhanceLevel
                                + " | Kh\u00f3a=" + e.isLocked
                                + " => " + (match ? "[S\u1ebc B\u00c1N]" : "[GI\u1eee L\u1ea0I]"));
                    }
                }
                System.err.println("==========================================================");
            } catch (Throwable t) {
                System.err.println("[AUTO-SELL-DIAG] L\u1ed7i: " + t.getMessage());
            }
        }
    }

    // =========================================================================
    // WATCHDOG CHONG KET 5 TANG LEO THANG
    // =========================================================================
    private static void checkAndRunWatchdog(RefCache ref, Object player, int currentMapId) {
        if (ref == null || player == null || currentMapId < 0) return;
        long now = System.currentTimeMillis();

        // Quy tắc 3: Chặn Watchdog đổi Map khi đang làm nhiệm vụ tại đúng Map mục tiêu
        if (trackedQuestId > 0 && currentMapId == currentTripGoalMap) {
            lastPositionChangeTime = now;
            return;
        }

        // [B7] Chi bo qua Watchdog khi nhan vat dang thuc su danh quai song trong tam hoac tien do quest vua tang
        if (now - lastProgressIncreaseTime < 15000L) {
            lastPositionChangeTime = now;
            return;
        }
        if (lockedTargetMob != null) {
            try {
                if (!ref.fIsDead.getBoolean(lockedTargetMob) && ref.fHp.getInt(lockedTargetMob) > 0) {
                    int d = (int) Math.hypot(
                            ref.fX.getInt(lockedTargetMob) - ref.fX.getInt(player),
                            ref.fY.getInt(lockedTargetMob) - ref.fY.getInt(player));
                    if (d <= 64 && (now - lastMobDamageDealtTime < 12000L)) {
                        lastPositionChangeTime = now;
                        return;
                    }
                }
            } catch (Throwable ignored) {}
        }

        long motionlessMs = now - lastPositionChangeTime;
        if (motionlessMs <= 20000L || (now - lastWatchdogActionTime < 8000L)) return;

        lastWatchdogActionTime = now;
        consecutiveStuckRecoveries++;
        releaseAllDirectionalKeys();
        tapKeyHelper(-7, 30, 60);

        if (consecutiveStuckRecoveries == 1) {
            System.err.println("[WATCHDOG][T\u1ea7ng 1/5] \u0110\u1ee9ng y\u00ean " + (motionlessMs / 1000)
                    + "s t\u1ea1i Map " + currentMapId + " -> H\u1ee7y l\u1ed9 tr\u00ecnh v\u00e0 c\u1ea5p l\u1ea1i Token n0208.");
            releaseAutoRouteToken();
            ensureControlToken(ref);
            lastRouteTime = 0;
        } else if (consecutiveStuckRecoveries == 2) {
            System.err.println("[WATCHDOG][T\u1ea7ng 2/5] V\u1eabn k\u1eb9t t\u1ea1i Map " + currentMapId
                    + " -> \u0110\u1ed5i \u0111i\u1ec3m tu\u1ea7n tra & \u00e9p \u0111i b\u1ed9 tr\u1ef1c ti\u1ebfp.");
            stopNavOnly(ref);
            useTeleportForCurrentTrip = false;
            resetLockedMonsterSpot();
            lastPatrolIndex++;
            lastRouteTime = 0;
            currentRoutingTargetMap = -1;
            // [FIX 2B] Neu dang chay Kich ban, chi go ket tai cho (Tang 1 & Tang 2), khong doi Map / khong hoan Quest
            if (questScriptMode) {
                consecutiveStuckRecoveries = 0;
            }
        } else if (consecutiveStuckRecoveries == 3) {
            System.err.println("[WATCHDOG][T\u1ea7ng 3/5] \u0110\u1ed5i sang Map \u1ee9ng vi\u00ean kh\u00e1c (t\u1ea1m lo\u1ea1i Map " + currentMapId + ").");
            markMapSearchedEmpty(currentMapId);
            searchedMissingNpcMaps.add(currentMapId);
            resetLockedMonsterSpot();
            lockedTargetMob = null;
            lastRouteTime = 0;
            currentRoutingTargetMap = -1;
        } else if (consecutiveStuckRecoveries == 4) {
            if (trackedQuestId > 0) {
                deferredQuestUntilMs.put(trackedQuestId, now + 90000L);
                System.err.println("[WATCHDOG][T\u1ea7ng 4/5] T\u1ea1m ho\u00e3n nhi\u1ec7m v\u1ee5 b\u1ebf t\u1eafc ID=" + trackedQuestId
                        + " trong 90s \u0111\u1ec3 chuy\u1ec3n sang nhi\u1ec7m v\u1ee5 kh\u00e1c / c\u00e0y c\u1ea5p.");
            }
            resetTeleportTripState(1, currentMapId);
            lastRouteTime = 0;
        } else {
            System.err.println("[WATCHDOG][T\u1ea7ng 5/5] C\u1ea3nh b\u00e1o k\u1eb9t s\u00e2u t\u1ea1i Map " + currentMapId
                    + " -> Xu\u1ea5t ch\u1ea9n \u0111o\u00e1n v\u00e0 reset to\u00e0n b\u1ed9 tr\u1ea1ng th\u00e1i \u0111\u1ecbnh tuy\u1ebfn.");
            currentAqStateLabel = "WATCHDOG_RECOVERY";
            sendAqStatusIfEnabled(ref, player, currentMapId, lastKnownPlayerLevel, true);
            releaseAutoRouteToken();
            searchedEmptyMaps.clear();
            searchedEmptyUntilMs.clear();
            searchedMissingNpcMaps.clear();
            resetLockedMonsterSpot();
            consecutiveStuckRecoveries = 0;
            lastPositionChangeTime = now;
        }
    }
    /**
     * Tu dong gui goi cong diem tiem nang truc tiep qua GlobalService (n0001.n0044)
     * @param levelsGained So cap vua tang (mac dinh 1 cap = nhan he so 1 voi cau hinh 5 diem)
     */
    private static void applyAutoPotentialPoints(RefCache ref, int levelsGained, String triggerReason) {
        if (ref == null || ref.clsGlobalSvc == null || ref.mGetSvc == null) return;
        int multiplier = Math.max(1, Math.min(levelsGained, 50));
        int configuredSum = autoPotentialPerLevel[0] + autoPotentialPerLevel[1]
                + autoPotentialPerLevel[2] + autoPotentialPerLevel[3];
        if (configuredSum <= 0) return;

        try {
            Object svc = invokeMethod(ref.mGetSvc, null);
            if (svc == null) return;

            // Tim ham cong tiem nang trong n0001.n0044: add_Base_Skill_Point(byte type, byte index, short value)
            Method mAddStatShort = null;
            Method mAddStatSingle = null;
            for (Method m : ref.clsGlobalSvc.getDeclaredMethods()) {
                if (Modifier.isStatic(m.getModifiers()) || m.getReturnType() != void.class) continue;
                Class<?>[] pt = m.getParameterTypes();
                if (pt.length == 3 && pt[0] == byte.class && pt[1] == byte.class && pt[2] == short.class) {
                    mAddStatShort = m;
                    mAddStatShort.setAccessible(true);
                    break;
                } else if (pt.length == 2 && pt[0] == byte.class && pt[1] == byte.class) {
                    mAddStatSingle = m;
                    mAddStatSingle.setAccessible(true);
                }
            }

            List<String> addedSummary = new ArrayList<>();
            for (int statIdx = 0; statIdx < 4; statIdx++) {
                int ptsPerLv = autoPotentialPerLevel[statIdx];
                if (ptsPerLv <= 0) continue;
                int pointsToAdd = ptsPerLv * multiplier;

                if (mAddStatShort != null) {
                    // type = 0 (Tiem nang), statIdx = 0..3, value = so diem cong
                    invokeMethod(mAddStatShort, svc, (byte) 0, (byte) statIdx, (short) pointsToAdd);
                    addedSummary.add(STAT_NAMES[statIdx] + " +" + pointsToAdd);
                    Thread.sleep(60);
                } else if (mAddStatSingle != null) {
                    for (int k = 0; k < pointsToAdd; k++) {
                        invokeMethod(mAddStatSingle, svc, (byte) 0, (byte) statIdx);
                        Thread.sleep(35);
                    }
                    addedSummary.add(STAT_NAMES[statIdx] + " +" + pointsToAdd);
                }
            }

            if (!addedSummary.isEmpty()) {
                System.err.println("[AUTO-POTENTIAL] (" + triggerReason + ") \u0110\u00e3 t\u1ef1 \u0111\u1ed9ng c\u1ed9ng ti\u1ec1m n\u0103ng: "
                        + String.join(", ", addedSummary));
                dirty = true;
            }
        } catch (Throwable t) {
            logReflectErrorOnce("applyAutoPotentialPoints", t);
        }
    }
    // =========================================================================
    // VONG TICK CHINH (TRA VE THOI GIAN NGU THICH UNG MS)
    // =========================================================================
    private static boolean hasHigherFarmSpot(RefCache ref, int playerLevel) {
        if (playerLevel < 135) return true;
        if (ref != null && ref.fDefaultSpots != null) {
            try {
                int[][] spots = (int[][]) ref.fDefaultSpots.get(null);
                if (spots != null) {
                    for (int[] s : spots) {
                        if (s != null && s.length >= 6 && s[1] >= playerLevel - 2) return true;
                    }
                    return false;
                }
            } catch (Throwable ignored) {}
        }
        return false;
    }

    private static void updatePlayerExpTracker(RefCache ref, Object player, long now) {
        if (ref == null || player == null || ref.clsPlayer == null) return;
        try {
            if (cachedPlayerExpField == null) {
                for (Field f : ref.clsPlayer.getDeclaredFields()) {
                    if (Modifier.isStatic(f.getModifiers())) continue;
                    if (f.getType() == long.class) {
                        f.setAccessible(true);
                        cachedPlayerExpField = f;
                        break;
                    }
                }
            }
            if (cachedPlayerExpField != null) {
                long exp = cachedPlayerExpField.getLong(player);
                if (lastKnownPlayerExp >= 0 && exp != lastKnownPlayerExp) {
                    lastExpChangeTime = now;
                }
                lastKnownPlayerExp = exp;
            } else if (now - lastMobDamageDealtTime < 5000L) {
                // Neu dang gay sat thuong len quai -> coi nhu EXP van dang tang
                lastExpChangeTime = now;
            }
        } catch (Throwable ignored) {}
    }

    private static long runAutoQuestTick() {
        try {
            ClassLoader cl = getGameClassLoader();
            if (cl == null) return 600L;

            RefCache ref = getRef(cl);
            if (ref == null || ref.clsQuest == null || ref.clsGameScr == null || ref.clsMainObj == null) {
                return 600L;
            }

            Object player = (ref.fPlayer != null) ? ref.fPlayer.get(null) : null;
            int playerLevel = getPlayerLevel(ref, player);

            if (player == null || playerLevel < 1) {
                currentAqStateLabel = "WAITING_LOGIN";
                currentAqDetailLabel = "Ch\u1edd v\u00e0o m\u00e0n h\u00ecnh game";
                releaseAllDirectionalKeys();
                logRateLimited("WAIT_LOGIN", 5000L,
                        "[AUTO-QUEST] \u0110ang ch\u1edd nh\u00e2n v\u1eadt \u0111\u0103ng nh\u1eadp v\u00e0o trong game (level=" + playerLevel + ")...");
                return 800L;
            }

            int currentMapId = getCurrentMapId(ref);
            if (currentMapId < 0) return 600L;

            long now = System.currentTimeMillis();
            purgeExpiredLocksAndEmptyMaps(now);
            updatePlayerExpTracker(ref, player, now);

            if (checkAndExecuteAutoSell(ref, player, currentMapId)) {
                sendAqStatusIfEnabled(ref, player, currentMapId, playerLevel, false);
                return 400L;
            }

            syncMapChangeWithNavigator(ref, currentMapId);
            int mobCountOnMap = recordActorsOnCurrentMap(ref, player, currentMapId);

            deferredQuestUntilMs.entrySet().removeIf(e -> now >= e.getValue());

            // [B11] Phat hien nhan vat vua len cap moi
            if (lastKnownPlayerLevel > 0 && playerLevel > lastKnownPlayerLevel) {
                int levelsGained = playerLevel - lastKnownPlayerLevel;
                System.err.println("[AUTO-QUEST] Nh\u00e2n v\u1eadt l\u00ean c\u1ea5p Lv." + lastKnownPlayerLevel + " -> Lv." + playerLevel
                        + " -> M\u1edf kh\u00f3a nhi\u1ec7m v\u1ee5 t\u1ea1m ho\u00e3n!");
                deferredQuestUntilMs.clear();
                questAcceptFailCounts.clear();
                questTurnInFailCounts.clear();
                if (!questScriptMode && (playerLevel % 2 == 0 || playerLevel == 24 || playerLevel == 35 || playerLevel == 60)) {
                    needCheckNewSubQuestsAtHub = true;
                    hubSubQuestCheckStartTime = now;
                    hubSubQuestArrivedTime = 0L;
                }
                lastLevelChangeTime = now;
                lastExpChangeTime = now;
                maxLevelCompletedSteadyMode = false;
                outOfHpPotions = false;
                outOfMpPotions = false;
                potionRestockFailedSafeMode = false;

                if (autoPotentialEnabled) {
                    applyAutoPotentialPoints(ref, levelsGained, "L\u00ean Lv." + playerLevel + " (+" + levelsGained + " c\u1ea5p)");
                }
            } else if (lastKnownPlayerLevel <= 0) {
                lastLevelChangeTime = now;
                lastExpChangeTime = now;
            }
            lastKnownPlayerLevel = playerLevel;

            // 2. [B9] Hoi sinh khi HP <= 0 & luu penalty rieng cho tung quest trong questLevelWhenDied
            int playerHp = ref.fHp.getInt(player);
            if (playerHp <= 0) {
                currentAqStateLabel = "DEAD_REVIVING";
                lockedTargetMob = null;
                releaseAllDirectionalKeys();
                if (now - lastDeathTimeMs > 4000L) {
                    lastDeathTimeMs = now;
                    mapBeforeDeath = currentMapId;
                    if (trackedQuestId > 0) {
                        questLevelWhenDied.put(trackedQuestId, playerLevel);
                        int deaths = questDeathCounts.getOrDefault(trackedQuestId, 0) + 1;
                        questDeathCounts.put(trackedQuestId, deaths);
                        int bonusLevelsNeeded = Math.min(5, deaths);
                        System.err.println("[AUTO-QUEST] Nh\u00e2n v\u1eadt t\u1eed tr\u1eadn l\u1ea7n #" + deaths
                                + " khi l\u00e0m NV ID=" + trackedQuestId + " t\u1ea1i Map " + currentMapId
                                + " (Lv." + playerLevel + ") -> H\u1ed3i sinh & \u0111\u1eb7t m\u1ee5c ti\u00eau c\u00e0y l\u00ean Lv."
                                + (playerLevel + bonusLevelsNeeded) + "!");
                    }
                }
                tapKeyHelper(-6, 35, 80);
                tapKeyHelper(-5, 35, 80);
                sendAqStatusIfEnabled(ref, player, currentMapId, playerLevel, false);
                return 450L;
            }

            // 3. Tu bom thuoc HP/MP neu dang can
            if (autoPotionEnabled) {
                checkAndAutoPotion(ref, player, currentMapId);
            }

            if (checkAndExecuteAutoSell(ref, player, currentMapId)) {
                sendAqStatusIfEnabled(ref, player, currentMapId, playerLevel, false);
                return 260L;
            }

            checkAndRunWatchdog(ref, player, currentMapId);

            // [KICH-BAN] Chay theo kich ban co dinh; tra -1 neu kich ban rong/da het -> roi xuong che do tu nhan dien
            if (questScriptMode) {
                long scriptSleep = runQuestScriptTick(ref, player, currentMapId, mobCountOnMap, playerLevel);
                if (scriptSleep > 0L) {
                    sendAqStatusIfEnabled(ref, player, currentMapId, playerLevel, false);
                    return scriptSleep;
                }
            }

            if ("farm".equalsIgnoreCase(autoQuestFilter) || "level".equalsIgnoreCase(autoQuestFilter)) {
                if (autoPotionEnabled && handleOutOfPotionsRestockOrSafeMode(ref, player, currentMapId, mobCountOnMap, playerLevel)) {
                    sendAqStatusIfEnabled(ref, player, currentMapId, playerLevel, false);
                    return 300L;
                }
                executeAutoLevelFarming(ref, player, currentMapId, mobCountOnMap, playerLevel,
                        "T\u1ef1 \u0111\u1ed9ng \u0111\u00e1nh qu\u00e1i theo Lv");
                sendAqStatusIfEnabled(ref, player, currentMapId, playerLevel, false);
                return (lockedTargetMob != null) ? 180L : 240L;
            }

            Object vecNew = ref.fVecNew.get(null);
            Object vecFinish = ref.fVecFinish.get(null);
            Object vecDoingMain = ref.fVecDoingMain.get(null);
            Object vecDoingSub = ref.fVecDoingSub.get(null);

            // 4. Uu tien 1: Tra nhiem vu da hoan thanh trong vecFinish
            Object finishQuest = pickQuestFromVector(ref, vecFinish, "main", true);
            if (finishQuest == null) {
                finishQuest = pickQuestFromVector(ref, vecFinish, "all", true);
            }
            if (finishQuest != null) {
                maxLevelCompletedSteadyMode = false;
                handleNpcQuestInteraction(ref, finishQuest, (byte) 1, "Tr\u1ea3 NV", player);
                sendAqStatusIfEnabled(ref, player, currentMapId, playerLevel, false);
                return 260L;
            }

            // 5. Kiem tra NV Chinh dang lam trong vecDoingMain
            Object readyMainQuest = !"sub".equalsIgnoreCase(autoQuestFilter)
                    ? pickReadyQuestFromVector(ref, vecDoingMain, "main", playerLevel, currentMapId) : null;
            Object anyMainQuest = (readyMainQuest != null) ? readyMainQuest
                    : (!"sub".equalsIgnoreCase(autoQuestFilter) ? pickQuestFromVector(ref, vecDoingMain, "main", false) : null);

            // 6. Nhan NV Chinh moi trong vecNew (Khi chua co NV Chinh san sang lam, hoac NPC dung ngay tren Map hien tai)
            if (!"sub".equalsIgnoreCase(autoQuestFilter)) {
                Object newMainQuest = pickQuestFromVector(ref, vecNew, "main", true);
                if (newMainQuest != null) {
                    int newNpcId = ref.fQNpcId.getInt(newMainQuest);
                    if (readyMainQuest == null || findNpcOnCurrentMap(ref, newNpcId) != null) {
                        maxLevelCompletedSteadyMode = false;
                        handleNpcQuestInteraction(ref, newMainQuest, (byte) 0, "Nh\u1eadn NV Ch\u00ednh", player);
                        sendAqStatusIfEnabled(ref, player, currentMapId, playerLevel, false);
                        return 260L;
                    }
                }
            }

            // 7. Kiem tra het thuoc truoc khi di danh quai lam NV
            if (autoPotionEnabled && handleOutOfPotionsRestockOrSafeMode(ref, player, currentMapId, mobCountOnMap, playerLevel)) {
                sendAqStatusIfEnabled(ref, player, currentMapId, playerLevel, false);
                return 300L;
            }

            // 8. THUC HIEN NGAY NHIEM VU CHINH TUYEN DANG SAN SANG (Khong bo di Map khac nhan NV Phu)
            if (readyMainQuest != null) {
                maxLevelCompletedSteadyMode = false;
                executeActiveQuestStep(ref, readyMainQuest, player, currentMapId, mobCountOnMap, playerLevel);
                sendAqStatusIfEnabled(ref, player, currentMapId, playerLevel, false);
                return (lockedTargetMob != null) ? 180L : 240L;
            }

            // 9. Khi KHONG co NV Chinh san sang -> Nhan va lam NV Phu
            Object activeSubQuest = null;
            if (!"main".equalsIgnoreCase(autoQuestFilter)) {
                Object newSubQuest = pickQuestFromVector(ref, vecNew, "sub", true);
                if (newSubQuest != null) {
                    maxLevelCompletedSteadyMode = false;
                    needCheckNewSubQuestsAtHub = false;
                    hubSubQuestArrivedTime = 0L;
                    handleNpcQuestInteraction(ref, newSubQuest, (byte) 0, "Nh\u1eadn NV Ph\u1ee5", player);
                    sendAqStatusIfEnabled(ref, player, currentMapId, playerLevel, false);
                    return 260L;
                }

                activeSubQuest = pickReadyQuestFromVector(ref, vecDoingSub, "sub", playerLevel, currentMapId);
                if (activeSubQuest != null) {
                    maxLevelCompletedSteadyMode = false;
                    executeActiveQuestStep(ref, activeSubQuest, player, currentMapId, mobCountOnMap, playerLevel);
                    sendAqStatusIfEnabled(ref, player, currentMapId, playerLevel, false);
                    return (lockedTargetMob != null) ? 180L : 240L;
                }
                activeSubQuest = pickQuestFromVector(ref, vecDoingSub, "sub", false);
            }

            // [B11] Ghe Hub quet NV Phu moi khi ranh
            if (needCheckNewSubQuestsAtHub && !"main".equalsIgnoreCase(autoQuestFilter)) {
                int hubMap = getQuestHubForPlayerLevel(playerLevel, currentMapId);
                if (now - hubSubQuestCheckStartTime < 45000L) {
                    if (currentMapId != hubMap) {
                        currentAqStateLabel = "CHECK_HUB_SUBQUEST";
                        currentAqDetailLabel = "\u0110ang v\u1ec1 Hub Map " + hubMap + " qu\u00e9t NV Ph\u1ee5 m\u1edbi";
                        triggerAutoRouteToQuest(ref, null, player, hubMap, mobCountOnMap);
                        sendAqStatusIfEnabled(ref, player, currentMapId, playerLevel, false);
                        return 260L;
                    } else {
                        if (hubSubQuestArrivedTime == 0L) hubSubQuestArrivedTime = now;
                        if (now - hubSubQuestArrivedTime < 5000L) {
                            currentAqStateLabel = "CHECK_HUB_SUBQUEST";
                            currentAqDetailLabel = "\u0110ang qu\u00e9t NPC t\u1ea1i Hub Map " + hubMap;
                            triggerAutoRouteToQuest(ref, null, player, hubMap, mobCountOnMap);
                            sendAqStatusIfEnabled(ref, player, currentMapId, playerLevel, false);
                            return 260L;
                        }
                    }
                }
                needCheckNewSubQuestsAtHub = false;
                hubSubQuestArrivedTime = 0L;
            }

            // Neu NV Chinh hoac NV Phu dang doi cay them Level -> Goi executeActiveQuestStep de bot tu di cay Level
            if (anyMainQuest != null) {
                maxLevelCompletedSteadyMode = false;
                executeActiveQuestStep(ref, anyMainQuest, player, currentMapId, mobCountOnMap, playerLevel);
                sendAqStatusIfEnabled(ref, player, currentMapId, playerLevel, false);
                return (lockedTargetMob != null) ? 180L : 240L;
            }

            if (activeSubQuest != null) {
                maxLevelCompletedSteadyMode = false;
                executeActiveQuestStep(ref, activeSubQuest, player, currentMapId, mobCountOnMap, playerLevel);
                sendAqStatusIfEnabled(ref, player, currentMapId, playerLevel, false);
                return (lockedTargetMob != null) ? 180L : 240L;
            }

            // 10. [B4] Chi vao COMPLETED_STEADY khi dat Max Cap (>=135 hoac het spot) VA EXP khong tang
            boolean hasDeferredQuests = !deferredQuestUntilMs.isEmpty();
            boolean expStalled = (now - lastExpChangeTime >= MAX_LEVEL_NO_PROGRESS_MS);
            boolean atMaxCap = (playerLevel >= 135) || !hasHigherFarmSpot(ref, playerLevel);
            if (!hasDeferredQuests && atMaxCap && expStalled && (now - lastLevelChangeTime >= MAX_LEVEL_NO_PROGRESS_MS)) {
                if (!maxLevelCompletedSteadyMode) {
                    maxLevelCompletedSteadyMode = true;
                    System.err.println("[AUTO-QUEST] \u0110\u00e3 \u0111\u1ea1t Max-Level (Lv." + playerLevel
                            + ") v\u00e0 EXP kh\u00f4ng t\u0103ng -> Chuy\u1ec3n sang COMPLETED_STEADY.");
                }
                if (now - lastSteadyQuestCheckTime > 30000L) {
                    lastSteadyQuestCheckTime = now;
                    deferredQuestUntilMs.clear();
                }
                executeAutoLevelFarming(ref, player, currentMapId, mobCountOnMap, playerLevel,
                        "Ch\u1ebf \u0111\u1ed9 \u1ed5n \u0111\u1ecbnh Max-Level");
                sendAqStatusIfEnabled(ref, player, currentMapId, playerLevel, false);
                return (lockedTargetMob != null) ? 220L : 600L;
            }

            // 11. Cay Level khi het viec hoac nhiem vu tiep theo doi cap cao hon
            executeAutoLevelFarming(ref, player, currentMapId, mobCountOnMap, playerLevel,
                    "C\u00e0y \u0111\u1ee7 Level m\u1edf kh\u00f3a Nhi\u1ec7m v\u1ee5 m\u1edbi");
            sendAqStatusIfEnabled(ref, player, currentMapId, playerLevel, false);
            return (lockedTargetMob != null) ? 190L : 260L;
        } catch (Throwable e) {
            logReflectErrorOnce("runAutoQuestTick", e);
            return 500L;
        }
    }

    // =========================================================================
    // K\u1ecaCH B\u1ea2N NHI\u1ec6M V\u1ee4 (SCRIPT) + RECORDER
    //   - File k\u1ecbch b\u1ea3n : aq_quest_script.json   (th\u01b0 m\u1ee5c save c\u1ee7a VM, ho\u1eb7c th\u01b0 m\u1ee5c ch\u1ea1y)
    //   - Con tr\u1ecf       : aq_quest_script_state.json (m\u1ed7i VM m\u1ed9t file, restart kh\u00f4ng m\u1ea5t ti\u1ebfn \u0111\u1ed9)
    //   - Recorder      : aq_quest_recorded.json (ghi l\u1ea1i t\u1eeb game th\u1eadt; d\u00f9ng AUTO_QUEST:script:adopt \u0111\u1ec3 bi\u1ebfn th\u00e0nh k\u1ecbch b\u1ea3n)
    // =========================================================================
    private static File questFileInVmDir(String name) {
        File k = getSharedKnowledgeFile();
        File parent = k.getAbsoluteFile().getParentFile();
        return (parent != null) ? new File(parent, name) : new File(name);
    }

    private static File resolveQuestScriptFile() {
        File f = questFileInVmDir("aq_quest_script.json");
        if (f.isFile()) return f;
        File g = new File("aq_quest_script.json");
        if (g.isFile()) return g;
        return f;
    }

    private static File questScriptStateFile() { return questFileInVmDir("aq_quest_script_state.json"); }

    private static File questRecordedFile() { return questFileInVmDir("aq_quest_recorded.json"); }

    private static int jsonInt(String obj, String key, int def) {
        try {
            Matcher m = Pattern.compile("\"" + key + "\"\\s*:\\s*(-?\\d+)").matcher(obj);
            if (m.find()) return Integer.parseInt(m.group(1));
        } catch (Throwable ignored) {}
        return def;
    }

    private static boolean jsonBool(String obj, String key, boolean def) {
        try {
            Matcher m = Pattern.compile("\"" + key + "\"\\s*:\\s*(true|false|1|0)").matcher(obj);
            if (m.find()) {
                String v = m.group(1);
                return "true".equals(v) || "1".equals(v);
            }
        } catch (Throwable ignored) {}
        return def;
    }

    private static String jsonString(String obj, String key, String def) {
        try {
            Matcher m = Pattern.compile("\"" + key + "\"\\s*:\\s*\"((?:\\\\.|[^\"\\\\])*)\"").matcher(obj);
            if (m.find()) return m.group(1).replace("\\\"", "\"").replace("\\\\", "\\");
        } catch (Throwable ignored) {}
        return def;
    }

    private static QStep parseQuestStepObject(String obj) {
        QStep s = new QStep();
        s.questId = jsonInt(obj, "questId", jsonInt(obj, "qId", jsonInt(obj, "id", -1)));
        s.name = jsonString(obj, "name", jsonString(obj, "questName", ""));
        s.main = jsonBool(obj, "main", jsonBool(obj, "isMain", true));
        s.type = jsonInt(obj, "type", jsonInt(obj, "qType", -1));
        s.minLevel = jsonInt(obj, "minLevel", jsonInt(obj, "reqLv", -1));
        int sharedNpc = jsonInt(obj, "npcId", -1);
        int sharedNpcMap = jsonInt(obj, "npcMapId", jsonInt(obj, "npcMap", -1));
        s.giveNpc = jsonInt(obj, "giveNpc", sharedNpc);
        s.giveMap = jsonInt(obj, "giveMap", sharedNpcMap);
        s.turnNpc = jsonInt(obj, "turnNpc", sharedNpc);
        s.turnMap = jsonInt(obj, "turnMap", sharedNpcMap >= 0 ? sharedNpcMap : s.giveMap);
        s.mobId = jsonInt(obj, "mobId", jsonInt(obj, "targetMobId", -1));
        s.mobMap = jsonInt(obj, "mobMap", jsonInt(obj, "targetMapId", jsonInt(obj, "mapId", -1)));
        s.count = jsonInt(obj, "count", jsonInt(obj, "maxCount", -1));
        s.spotX = jsonInt(obj, "spotX", -1);
        s.spotY = jsonInt(obj, "spotY", -1);
        return s;
    }

    /** \u0110\u1ecdc m\u1ea3ng "steps":[{...},{...}] ho\u1eb7c "quests":[{...}] (ho\u1eb7c m\u1ed9t m\u1ea3ng JSON tr\u1ea7n). */
    private static List<QStep> parseQuestScriptJson(String json) {
        List<QStep> out = new ArrayList<>();
        if (json == null) return out;
        int p = json.indexOf("\"steps\"");
        if (p < 0) p = json.indexOf("\"quests\"");
        int arr = json.indexOf('[', p >= 0 ? p : 0);
        if (arr < 0) return out;
        int n = json.length();
        int i = arr + 1;
        while (i < n) {
            char c = json.charAt(i);
            if (c == ']') break;
            if (c == '{') {
                int depth = 0;
                boolean inStr = false;
                int j = i;
                for (; j < n; j++) {
                    char d = json.charAt(j);
                    if (inStr) {
                        if (d == '\\') j++;
                        else if (d == '"') inStr = false;
                    } else if (d == '"') {
                        inStr = true;
                    } else if (d == '{') {
                        depth++;
                    } else if (d == '}') {
                        depth--;
                        if (depth == 0) break;
                    }
                }
                if (j >= n) break;
                QStep s = parseQuestStepObject(json.substring(i, j + 1));
                if (s.questId >= 0) out.add(s);
                i = j + 1;
            } else {
                i++;
            }
        }
        return out;
    }

    private static String stepToJson(QStep r) {
        return "{\"questId\":" + r.questId
                + ",\"name\":\"" + escapeJson(r.name) + "\""
                + ",\"main\":" + (r.main ? 1 : 0)
                + ",\"type\":" + r.type
                + ",\"minLevel\":" + r.minLevel
                + ",\"giveNpc\":" + r.giveNpc + ",\"giveMap\":" + r.giveMap
                + ",\"turnNpc\":" + r.turnNpc + ",\"turnMap\":" + r.turnMap
                + ",\"mobId\":" + r.mobId + ",\"mobMap\":" + r.mobMap
                + ",\"count\":" + r.count
                + ",\"spotX\":" + r.spotX + ",\"spotY\":" + r.spotY + "}";
    }

    private static void writeTextFileAtomic(File f, String text) throws Exception {
        File tmp = new File(f.getPath() + ".tmp");
        Files.write(tmp.toPath(), text.getBytes(StandardCharsets.UTF_8));
        Files.move(tmp.toPath(), f.toPath(), StandardCopyOption.REPLACE_EXISTING);
    }

    private static void saveQuestScriptState() {
        try {
            List<QStep> steps = questScript;
            int idx = scriptIdx;
            int qid = (steps != null && idx >= 0 && idx < steps.size()) ? steps.get(idx).questId : -1;
            writeTextFileAtomic(questScriptStateFile(), "{\"idx\":" + idx + ",\"questId\":" + qid + "}\n");
        } catch (Throwable t) {
            logReflectErrorOnce("saveQuestScriptState", t);
        }
    }

    private static void scriptClearOverride() {
        scriptOverrideQuestId = -1;
        scriptOverrideMobId = -1;
        scriptOverrideMapId = -1;
        scriptOverrideNpcMap = -1;
        scriptOverrideMinLv = -1;
        scriptOverrideSpotX = -1;
        scriptOverrideSpotY = -1;
    }

    private static void scriptSetOverride(QStep s, int npcMapForThisPhase) {
        scriptOverrideQuestId = s.questId;
        scriptOverrideMobId = s.mobId;
        scriptOverrideMapId = s.mobMap;
        scriptOverrideNpcMap = npcMapForThisPhase;
        scriptOverrideMinLv = s.minLevel;
        scriptOverrideSpotX = s.spotX;
        scriptOverrideSpotY = s.spotY;
    }

    private static void resetScriptStepState() {
        scriptStepQuestId = Integer.MIN_VALUE;
        scriptStepSeen = false;
        scriptAbsentSinceMs = 0L;
        scriptWaitUnlockSinceMs = 0L;
        scriptClearOverride();
    }

    private static synchronized int loadQuestScript(boolean verbose) {
        questScriptLoadedOnce = true;
        try {
            File f = resolveQuestScriptFile();
            if (!f.isFile()) {
                questScript = new ArrayList<>();
                scriptIdx = 0;
                if (verbose) System.err.println("[KICH-BAN] Kh\u00f4ng th\u1ea5y file " + f.getAbsolutePath());
                return 0;
            }
            String json = new String(Files.readAllBytes(f.toPath()), StandardCharsets.UTF_8);
            List<QStep> steps = parseQuestScriptJson(json);
            questScript = steps;
            questScriptByQuestId.clear();
            for (QStep step : steps) {
                if (step.questId >= 0) {
                    questScriptByQuestId.put(step.questId, step);
                }
                if (step.giveNpc != -1 && step.giveMap >= 0) {
                    knownNpcMap.putIfAbsent(step.giveNpc, step.giveMap);
                }
                if (step.turnNpc != -1 && step.turnMap >= 0) {
                    knownNpcMap.putIfAbsent(step.turnNpc, step.turnMap);
                }
                if (step.mobId >= 0 && step.mobMap >= 0) {
                    knownMobMap.putIfAbsent(step.mobId, step.mobMap);
                }
            }

            int idx = 0;
            int savedQ = -1;
            File st = questScriptStateFile();
            if (st.isFile()) {
                String sj = new String(Files.readAllBytes(st.toPath()), StandardCharsets.UTF_8);
                idx = jsonInt(sj, "idx", 0);
                savedQ = jsonInt(sj, "questId", -1);
            }
            if (savedQ >= 0 && (idx < 0 || idx >= steps.size() || steps.get(idx).questId != savedQ)) {
                for (int i = 0; i < steps.size(); i++) {
                    if (steps.get(i).questId == savedQ) { idx = i; break; }
                }
            }
            if (idx < 0) idx = 0;
            if (idx > steps.size()) idx = steps.size();
            scriptIdx = idx;
            resetScriptStepState();
            System.err.println("[KICH-BAN] \u0110\u00e3 n\u1ea1p " + steps.size() + " b\u01b0\u1edbc t\u1eeb " + f.getAbsolutePath()
                    + " | con tr\u1ecf = b\u01b0\u1edbc " + (idx + 1));
            return steps.size();
        } catch (Throwable t) {
            logReflectErrorOnce("loadQuestScript", t);
            return -1;
        }
    }

    private static void ensureQuestScriptLoaded() {
        if (!questScriptLoadedOnce) loadQuestScript(true);
    }

    private static void scriptPrintStatus() {
        List<QStep> steps = questScript;
        int n = (steps != null) ? steps.size() : 0;
        System.err.println("[KICH-BAN] mode=" + questScriptMode + " | record=" + questRecordEnabled
                + " | s\u1ed1 b\u01b0\u1edbc=" + n + " | con tr\u1ecf=" + (scriptIdx + 1) + "/" + n);
        if (steps != null && scriptIdx >= 0 && scriptIdx < n) {
            QStep s = steps.get(scriptIdx);
            System.err.println("[KICH-BAN] B\u01b0\u1edbc hi\u1ec7n t\u1ea1i: " + stepToJson(s));
        } else if (n > 0) {
            System.err.println("[KICH-BAN] \u0110\u00e3 ch\u1ea1y h\u1ebft k\u1ecbch b\u1ea3n (d\u00f9ng AUTO_QUEST:goto:#N \u0111\u1ec3 \u0111\u1ed5i con tr\u1ecf).");
        }
    }

    private static void scriptPrintList() {
        List<QStep> steps = questScript;
        if (steps == null || steps.isEmpty()) {
            System.err.println("[KICH-BAN] K\u1ecbch b\u1ea3n tr\u1ed1ng.");
            return;
        }
        for (int i = 0; i < steps.size(); i++) {
            QStep s = steps.get(i);
            System.err.println("[KICH-BAN] " + (i == scriptIdx ? ">>" : "  ") + " #" + (i + 1)
                    + " NV" + s.questId + " " + (s.main ? "[Ch\u00ednh]" : "[Ph\u1ee5]") + " '" + s.name + "'"
                    + " | Lv>=" + s.minLevel + " | nh\u1eadn=Map" + s.giveMap + "/NPC" + s.giveNpc
                    + " | qu\u00e1i=" + s.mobId + "@Map" + s.mobMap + " x" + s.count
                    + " | tr\u1ea3=Map" + s.turnMap + "/NPC" + s.turnNpc);
        }
    }

    private static void scriptAdoptRecorded() {
        try {
            File rec = questRecordedFile();
            if (!rec.isFile()) {
                System.err.println("[KICH-BAN] Ch\u01b0a c\u00f3 file ghi: " + rec.getAbsolutePath());
                return;
            }
            File dst = questFileInVmDir("aq_quest_script.json");
            if (dst.isFile()) {
                Files.copy(dst.toPath(), new File(dst.getPath() + ".bak").toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
            Files.copy(rec.toPath(), dst.toPath(), StandardCopyOption.REPLACE_EXISTING);
            System.err.println("[KICH-BAN] \u0110\u00e3 copy " + rec.getName() + " -> " + dst.getName()
                    + " (b\u1ea3n c\u0169 l\u01b0u .bak). H\u00e3y m\u1edf file \u0111\u1ec3 ch\u1ec9nh tay r\u1ed3i AUTO_QUEST:script:load.");
            File st = questScriptStateFile();
            if (st.isFile()) st.delete();
            loadQuestScript(true);
        } catch (Throwable t) {
            logReflectErrorOnce("scriptAdoptRecorded", t);
        }
    }

    /** AUTO_QUEST:goto:#N (v\u1ecb tr\u00ed th\u1ee9 N trong danh s\u00e1ch) ho\u1eb7c AUTO_QUEST:goto:<questId> */
    private static void scriptGoto(String arg) {
        ensureQuestScriptLoaded();
        List<QStep> steps = questScript;
        if (steps == null || steps.isEmpty()) {
            System.err.println("[KICH-BAN] K\u1ecbch b\u1ea3n tr\u1ed1ng, kh\u00f4ng goto \u0111\u01b0\u1ee3c.");
            return;
        }
        int target = -1;
        try {
            String a = arg == null ? "" : arg.trim();
            if (a.startsWith("#")) {
                target = Integer.parseInt(a.substring(1).trim()) - 1;
            } else {
                int id = Integer.parseInt(a);
                for (int i = 0; i < steps.size(); i++) {
                    if (steps.get(i).questId == id) { target = i; break; }
                }
                if (target < 0 && id >= 1 && id <= steps.size()) {
                    target = id - 1;
                }
            }
        } catch (Throwable ignored) {}
        if (target < 0 || target >= steps.size()) {
            System.err.println("[KICH-BAN] goto '" + arg + "' kh\u00f4ng h\u1ee3p l\u1ec7 (d\u00f9ng #N ho\u1eb7c questId c\u00f3 trong k\u1ecbch b\u1ea3n).");
            return;
        }
        scriptIdx = target;
        resetScriptStepState();
        saveQuestScriptState();
        System.err.println("[KICH-BAN] \u0110\u00e3 \u0111\u1eb7t con tr\u1ecf = b\u01b0\u1edbc " + (target + 1) + "/" + steps.size()
                + " (NV" + steps.get(target).questId + ")");
    }

    private static void handleScriptCommand(String[] parts) {
        String sub = parts.length > 1 ? parts[1].trim().toLowerCase() : "status";
        switch (sub) {
            case "1": case "on": case "start":
                loadQuestScript(true);
                questScriptMode = true;
                autoQuestFilter = "script";
                autoQuestEnabled = true;
                startAutoQuestLoop();
                System.err.println("[KICH-BAN] B\u1eadt ch\u1ebf \u0111\u1ed9 ch\u1ea1y theo k\u1ecbch b\u1ea3n.");
                break;
            case "0": case "off": case "stop":
                questScriptMode = false;
                if ("script".equalsIgnoreCase(autoQuestFilter)) {
                    autoQuestFilter = "all";
                }
                scriptClearOverride();
                System.err.println("[KICH-BAN] T\u1eaft ch\u1ebf \u0111\u1ed9 k\u1ecbch b\u1ea3n (quay v\u1ec1 t\u1ef1 nh\u1eadn di\u1ec7n).");
                break;
            case "load":
                loadQuestScript(true);
                break;
            case "list":
                ensureQuestScriptLoaded();
                scriptPrintList();
                break;
            case "adopt":
                scriptAdoptRecorded();
                break;
            case "reset":
                scriptIdx = 0;
                resetScriptStepState();
                saveQuestScriptState();
                System.err.println("[KICH-BAN] \u0110\u00e3 \u0111\u1eb7t con tr\u1ecf v\u1ec1 b\u01b0\u1edbc 1.");
                break;
            default:
                ensureQuestScriptLoaded();
                scriptPrintStatus();
        }
    }

    private static void advanceScript(String reason) {
        List<QStep> steps = questScript;
        scriptIdx = scriptIdx + 1;
        System.err.println("[KICH-BAN] " + reason + " -> b\u01b0\u1edbc " + (scriptIdx + 1) + "/" + (steps != null ? steps.size() : 0));
        deferredQuestUntilMs.clear();
        questAcceptFailCounts.clear();
        questTurnInFailCounts.clear();
        lockedTargetMob = null;
        resetScriptStepState();
        saveQuestScriptState();
    }

    private static Object findQuestById(RefCache ref, Object vec, int qid) {
        if (vec == null || ref == null) return null;
        try {
            Method[] vm = getVectorMethods(vec);
            if (vm == null) return null;
            int size = (Integer) invokeMethod(vm[0], vec);
            for (int i = 0; i < size; i++) {
                Object q = invokeMethod(vm[1], vec, i);
                if (q != null && ref.clsQuest.isInstance(q) && ref.fQId.getInt(q) == qid) return q;
            }
        } catch (Throwable ignored) {}
        return null;
    }

    private static void scriptTagDetail(QStep s) {
        List<QStep> steps = questScript;
        currentAqDetailLabel = "[KB " + (scriptIdx + 1) + "/" + (steps != null ? steps.size() : 0)
                + " NV" + s.questId + "] " + currentAqDetailLabel;
    }

    private static boolean scriptPotionGate(RefCache ref, Object player, int mapId, int mobCount, int lv) {
        return autoPotionEnabled && handleOutOfPotionsRestockOrSafeMode(ref, player, mapId, mobCount, lv);
    }

    private static long scriptFarm(RefCache ref, Object player, int mapId, int mobCount, int lv, QStep s, String why) {
        scriptClearOverride();
        executeAutoLevelFarming(ref, player, mapId, mobCount, lv, "[K\u1ecbch b\u1ea3n] " + why);
        scriptTagDetail(s);
        return (lockedTargetMob != null) ? 190L : 260L;
    }

    /**
     * M\u1ed7i tick ch\u1ec9 x\u00e9t \u0111\u00fang b\u01b0\u1edbc SCRIPT[scriptIdx].
     * Tr\u1ea3 v\u1ec1 th\u1eddi gian ng\u1ee7 (ms) n\u1ebfu \u0111\u00e3 x\u1eed l\u00fd tick; tr\u1ea3 v\u1ec1 -1 n\u1ebfu kh\u00f4ng c\u00f3 vi\u1ec7c cho k\u1ecbch b\u1ea3n
     * (r\u1ed7ng / \u0111\u00e3 h\u1ebft) -> nh\u01b0\u1eddng cho ch\u1ebf \u0111\u1ed9 t\u1ef1 nh\u1eadn di\u1ec7n c\u0169.
     */
    private static long runQuestScriptTick(RefCache ref, Object player, int currentMapId,
                                           int mobCountOnMap, int playerLevel) throws Exception {
        ensureQuestScriptLoaded();
        List<QStep> steps = questScript;
        if (steps == null || steps.isEmpty()) {
            logRateLimited("SCRIPT_EMPTY", 20000L,
                    "[KICH-BAN] Ch\u01b0a c\u00f3 k\u1ecbch b\u1ea3n (aq_quest_script.json) -> d\u00f9ng ch\u1ebf \u0111\u1ed9 t\u1ef1 nh\u1eadn di\u1ec7n.");
            scriptClearOverride();
            return -1L;
        }
        if (scriptIdx < 0) scriptIdx = 0;
        if (scriptIdx >= steps.size()) {
            logRateLimited("SCRIPT_DONE", 30000L,
                    "[KICH-BAN] \u0110\u00e3 ch\u1ea1y h\u1ebft k\u1ecbch b\u1ea3n -> d\u00f9ng ch\u1ebf \u0111\u1ed9 t\u1ef1 nh\u1eadn di\u1ec7n cho NV c\u00f2n l\u1ea1i.");
            scriptClearOverride();
            return -1L;
        }

        Object vecNew = ref.fVecNew.get(null);
        Object vecFinish = ref.fVecFinish.get(null);
        Object vecDoingMain = ref.fVecDoingMain.get(null);
        Object vecDoingSub = ref.fVecDoingSub.get(null);
        if (vecNew == null && vecFinish == null && vecDoingMain == null && vecDoingSub == null) return 500L;

        // [AUTO-SYNC CON TRO KICH BAN THONG MINH]
        // Neu buoc hien tai khong co trong 4 danh sach nhung mot buoc khac trong kich ban DANG co trong Finish / Doing / New,
        // dong bo ngay con tro scriptIdx toi buoc thuc te cua nhan vat ma khong can cho 45s!
        QStep s = steps.get(scriptIdx);
        long now = System.currentTimeMillis();
        Object qFin = findQuestById(ref, vecFinish, s.questId);
        Object qDoing = findQuestById(ref, vecDoingMain, s.questId);
        if (qDoing == null) qDoing = findQuestById(ref, vecDoingSub, s.questId);
        Object qNew = findQuestById(ref, vecNew, s.questId);

        if (qFin == null && qDoing == null && qNew == null) {
            int matchedIdx = -1;
            // Uu tien 1: Nhiem vu trong kich ban dang cho Tra (Finish) hoac Dang lam (Doing)
            for (int j = 0; j < steps.size(); j++) {
                int qj = steps.get(j).questId;
                if (findQuestById(ref, vecFinish, qj) != null
                        || findQuestById(ref, vecDoingMain, qj) != null
                        || findQuestById(ref, vecDoingSub, qj) != null) {
                    matchedIdx = j;
                    break;
                }
            }
            // Uu tien 2: Nhiem vu trong kich ban dang co the Nhan (New)
            if (matchedIdx < 0) {
                for (int j = 0; j < steps.size(); j++) {
                    int qj = steps.get(j).questId;
                    if (findQuestById(ref, vecNew, qj) != null) {
                        matchedIdx = j;
                        break;
                    }
                }
            }
            // Uu tien 3: Neu con tro dang o buoc co minLevel thap hon han cap do nhan vat (>5 cap) va chua tung thay -> nhay toi moc phu hop voi Level hien tai
            if (matchedIdx < 0 && !scriptStepSeen && s.minLevel > 0 && playerLevel > s.minLevel + 4) {
                for (int j = scriptIdx + 1; j < steps.size(); j++) {
                    QStep cand = steps.get(j);
                    if (cand.minLevel >= playerLevel - 2) {
                        matchedIdx = j;
                        break;
                    }
                }
            }

            if (matchedIdx >= 0 && matchedIdx != scriptIdx) {
                System.err.println("[KICH-BAN] Dong bo thong minh con tro: buoc " + (scriptIdx + 1)
                        + " (NV" + s.questId + ") -> buoc " + (matchedIdx + 1)
                        + " (NV" + steps.get(matchedIdx).questId + " '" + steps.get(matchedIdx).name + "')");
                scriptIdx = matchedIdx;
                resetScriptStepState();
                saveQuestScriptState();
                s = steps.get(scriptIdx);
                qFin = findQuestById(ref, vecFinish, s.questId);
                qDoing = findQuestById(ref, vecDoingMain, s.questId);
                if (qDoing == null) qDoing = findQuestById(ref, vecDoingSub, s.questId);
                qNew = findQuestById(ref, vecNew, s.questId);
            }
        }

        if (scriptStepQuestId != s.questId) {
            resetScriptStepState();
            scriptStepQuestId = s.questId;
            System.err.println("[KICH-BAN] B\u01b0\u1edbc " + (scriptIdx + 1) + "/" + steps.size() + ": NV" + s.questId
                    + " '" + s.name + "' | Lv>=" + s.minLevel + " | nh\u1eadn=Map" + s.giveMap
                    + " | qu\u00e1i=" + s.mobId + "@Map" + s.mobMap + " | tr\u1ea3=Map" + s.turnMap);
        }

        // 1) Nhi\u1ec7m v\u1ee5 \u0111\u00e3 xong -> \u0111i tr\u1ea3
        if (qFin != null) {
            scriptStepSeen = true;
            scriptAbsentSinceMs = 0L;
            scriptWaitUnlockSinceMs = 0L;
            scriptSetOverride(s, s.turnMap);
            handleNpcQuestInteraction(ref, qFin, (byte) 1, "[K\u1ecbch b\u1ea3n] Tr\u1ea3 NV", player);
            scriptTagDetail(s);
            return 260L;
        }

        // 2) \u0110ang l\u00e0m -> c\u00e0y \u0111\u1ee7 Lv r\u1ed3i m\u1edbi l\u00e0m, sau \u0111\u00f3 ch\u1ea1y b\u01b0\u1edbc l\u00e0m nhi\u1ec7m v\u1ee5 v\u1edbi d\u1eef li\u1ec7u ghi \u0111\u00e8
        if (qDoing != null) {
            scriptStepSeen = true;
            scriptAbsentSinceMs = 0L;
            scriptWaitUnlockSinceMs = 0L;
            if (s.minLevel > 0 && playerLevel < s.minLevel) {
                if (scriptPotionGate(ref, player, currentMapId, mobCountOnMap, playerLevel)) return 300L;
                return scriptFarm(ref, player, currentMapId, mobCountOnMap, playerLevel, s,
                        "C\u00e0y l\u00ean Lv." + s.minLevel + " tr\u01b0\u1edbc khi l\u00e0m NV" + s.questId);
            }
            if (scriptPotionGate(ref, player, currentMapId, mobCountOnMap, playerLevel)) return 300L;
            scriptSetOverride(s, s.turnMap);
            executeActiveQuestStep(ref, qDoing, player, currentMapId, mobCountOnMap, playerLevel);
            scriptTagDetail(s);
            return (lockedTargetMob != null) ? 180L : 240L;
        }

        // 3) C\u00f3 th\u1ec3 nh\u1eadn -> \u0111i t\u1edbi NPC nh\u1eadn
        if (qNew != null) {
            scriptStepSeen = true;
            scriptAbsentSinceMs = 0L;
            scriptWaitUnlockSinceMs = 0L;
            if (s.minLevel > 0 && playerLevel < s.minLevel) {
                if (scriptPotionGate(ref, player, currentMapId, mobCountOnMap, playerLevel)) return 300L;
                return scriptFarm(ref, player, currentMapId, mobCountOnMap, playerLevel, s,
                        "C\u00e0y l\u00ean Lv." + s.minLevel + " tr\u01b0\u1edbc khi nh\u1eadn NV" + s.questId);
            }
            scriptSetOverride(s, s.giveMap);
            handleNpcQuestInteraction(ref, qNew, (byte) 0, "[K\u1ecbch b\u1ea3n] Nh\u1eadn NV", player);
            scriptTagDetail(s);
            return 260L;
        }

        // 4) Kh\u00f4ng n\u1eb1m trong vector n\u00e0o
        scriptClearOverride();
        if (scriptStepSeen) {
            scriptWaitUnlockSinceMs = 0L;
            if (scriptAbsentSinceMs == 0L) scriptAbsentSinceMs = now;
            if (now - scriptAbsentSinceMs >= SCRIPT_GONE_CONFIRM_MS) {
                advanceScript("NV" + s.questId + " \u0111\u00e3 ho\u00e0n th\u00e0nh");
            }
            return 250L;
        }

        // Nếu nhân vật đang có bất kỳ nhiệm vụ thực tế nào khác trong vecFinish / vecDoingMain / vecNew / vecDoingSub,
        // nhường ngay cho bộ giải nhiệm vụ thông minh xử lý nhiệm vụ đó để không đứng chờ!
        if (pickQuestFromVector(ref, vecFinish, "all", true) != null
                || pickQuestFromVector(ref, vecDoingMain, "main", false) != null
                || pickQuestFromVector(ref, vecNew, "all", true) != null
                || pickQuestFromVector(ref, vecDoingSub, "sub", false) != null) {
            for (int k = scriptIdx + 1; k < steps.size(); k++) {
                QStep sk = steps.get(k);
                if (sk.minLevel <= playerLevel + 2) {
                    scriptIdx = k;
                } else {
                    break;
                }
            }
            return -1L;
        }

        // Nếu chưa đủ Lv cho bước kịch bản hiện tại -> đi cày Level ngay lập tức!
        if (s.minLevel > 0 && playerLevel < s.minLevel) {
            scriptWaitUnlockSinceMs = 0L;
            if (scriptPotionGate(ref, player, currentMapId, mobCountOnMap, playerLevel)) return 300L;
            return scriptFarm(ref, player, currentMapId, mobCountOnMap, playerLevel, s,
                    "C\u00e0y l\u00ean Lv." + s.minLevel + " \u0111\u1ec3 m\u1edf NV" + s.questId + " (" + s.name + ")");
        }

        // Nếu nhân vật đã vượt cấp của bước kịch bản này (> minLevel + 2) và chưa có NV -> tiến sang bước tiếp theo phù hợp cấp độ
        if (s.minLevel > 0 && playerLevel > s.minLevel + 2 && scriptIdx < steps.size() - 1) {
            advanceScript("Nh\u00e2n v\u1eadt Lv." + playerLevel + " \u0111\u00e3 v\u01b0\u1ee3t m\u1ed1c NV" + s.questId + " (Lv." + s.minLevel + ")");
            return 180L;
        }

        if (scriptWaitUnlockSinceMs == 0L) scriptWaitUnlockSinceMs = now;
        long waited = now - scriptWaitUnlockSinceMs;
        if (waited >= 10000L) {
            advanceScript("NV" + s.questId + " \u0111\u00e3 ho\u00e0n t\u1ea5t ho\u1eb7c ch\u01b0a m\u1edf sau " + (waited / 1000) + "s -> chuy\u1ec3n b\u01b0\u1edbc k\u1ebf");
            return 200L;
        }

        currentAqStateLabel = "SCRIPT_CHECK_NPC";
        currentAqDetailLabel = "\u0110\u1ebfn Map " + s.giveMap + " g\u1eb7p NPC " + s.giveNpc + " m\u1edf NV" + s.questId;
        if (s.giveMap >= 0 && currentMapId != s.giveMap) {
            triggerAutoRouteToQuest(ref, null, player, s.giveMap, mobCountOnMap);
        } else {
            Object giveNpcObj = findNpcOnCurrentMap(ref, s.giveNpc);
            if (giveNpcObj != null) {
                int px = ref.fX.getInt(player);
                int py = ref.fY.getInt(player);
                int nx = ref.fX.getInt(giveNpcObj);
                int ny = ref.fY.getInt(giveNpcObj);
                if (Math.hypot(nx - px, ny - py) > 48) {
                    movePlayerCloseToTarget(ref, giveNpcObj, player, currentMapId);
                } else {
                    releaseAllDirectionalKeys();
                    ref.fFocus.set(null, giveNpcObj);
                    tapKeyHelper(-5, 25, 45);
                }
            } else {
                releaseAllDirectionalKeys();
            }
        }
        scriptTagDetail(s);
        return 300L;
    }

    // -------------------------------------------------------------------------
    // RECORDER: v\u1eeba ch\u01a1i v\u1eeba ghi l\u1ea1i th\u00f4ng tin nhi\u1ec7m v\u1ee5 th\u1eadt t\u1eeb game
    // -------------------------------------------------------------------------
    private static boolean isMobTemplateOnMap(RefCache ref, int mobTemplateId) {
        try {
            Object vecActors = (ref.fVecActors != null) ? ref.fVecActors.get(null) : null;
            if (vecActors == null) return false;
            Method[] vm = getVectorMethods(vecActors);
            if (vm == null) return false;
            int sz = (Integer) invokeMethod(vm[0], vecActors);
            for (int i = 0; i < sz; i++) {
                Object a = invokeMethod(vm[1], vecActors, i);
                if (a != null && ref.clsMonsterBase.isInstance(a) && ref.fMobTemplateId.getInt(a) == mobTemplateId) {
                    return true;
                }
            }
        } catch (Throwable ignored) {}
        return false;
    }

    /** {id m\u1ee5c ti\u00eau \u0111\u1ea7u ti\u00ean ch\u01b0a xong, s\u1ed1 l\u01b0\u1ee3ng c\u1ea7n, t\u1ed5ng \u0111\u00e3 \u0111\u1ea1t} */
    private static int[] questProgressInfo(RefCache ref, Object q) {
        int firstId = -1, firstMax = -1, curSum = 0;
        try {
            Object t = ref.fQTargets.get(q);
            Object c = ref.fQCurCounts.get(q);
            Object m = ref.fQMaxCounts.get(q);
            if (t != null && c != null && m != null) {
                int len = Math.min(Array.getLength(t), Math.min(Array.getLength(c), Array.getLength(m)));
                for (int i = 0; i < len; i++) {
                    int cur = ((Number) Array.get(c, i)).intValue();
                    int max = ((Number) Array.get(m, i)).intValue();
                    int tid = ((Number) Array.get(t, i)).intValue();
                    if (max > 0) curSum += Math.min(cur, max);
                    if (firstId < 0 && tid >= 0 && (max <= 0 || cur < max)) {
                        firstId = tid;
                        firstMax = max;
                    }
                }
            }
        } catch (Throwable ignored) {}
        return new int[]{firstId, firstMax, curSum};
    }

    private static boolean recordNpcPresent(RefCache ref, Object q) {
        try {
            int npcId = ref.fQNpcId.getInt(q);
            if (findNpcOnCurrentMap(ref, npcId) != null) return true;
            if (ref.fQNpcAlt != null) {
                int alt = ((Number) ref.fQNpcAlt.get(q)).intValue();
                if (alt != 0 && alt != npcId && findNpcOnCurrentMap(ref, alt) != null) return true;
            }
        } catch (Throwable ignored) {}
        return false;
    }

    private static void recordScanVector(RefCache ref, Object vec, int kind, int mapId, int lv,
                                         int px, int py, Set<Integer> presentNow) {
        if (vec == null) return;
        try {
            Method[] vm = getVectorMethods(vec);
            if (vm == null) return;
            int size = (Integer) invokeMethod(vm[0], vec);
            for (int i = 0; i < size; i++) {
                Object q = invokeMethod(vm[1], vec, i);
                if (q == null || !ref.clsQuest.isInstance(q)) continue;
                if (isIgnoredNpcOrTinhTuQuest(ref, q)) continue;

                int qid = ref.fQId.getInt(q);
                presentNow.add(qid);
                QStep r = recordedQuests.get(qid);
                if (r == null) {
                    r = new QStep();
                    r.questId = qid;
                    recordedQuests.put(qid, r);
                }
                String nm = (String) ref.fQName.get(q);
                if (nm != null && !nm.trim().isEmpty()) r.name = nm.trim();
                r.main = ref.fQIsMain.getBoolean(q);
                int qType = ((Number) ref.fQType.get(q)).intValue();
                r.type = qType;
                int npcId = ref.fQNpcId.getInt(q);
                boolean npcHere = recordNpcPresent(ref, q);

                if (kind == 0) {
                    r.seenNew = true;
                    if (r.giveMap < 0 && npcHere) {
                        r.giveMap = mapId;
                        r.giveNpc = npcId;
                    }
                } else if (kind == 1) {
                    r.seenDoing = true;
                    if (r.seq < 0) r.seq = ++recordSeq;
                    if (r.minLevel < 0) r.minLevel = lv;
                    int[] pi = questProgressInfo(ref, q);
                    if (r.count < 0 && pi[1] > 0) r.count = pi[1];
                    if (qType == 1) {
                        if (r.mobId < 0 && pi[0] >= 0) r.mobId = pi[0];
                    } else if (r.mobId < 0 && qType != 2 && qType != 3) {
                        int guess = inferMonsterIdFromQuestText(ref, q, extractAllTextFromQuest(ref, q));
                        if (guess >= 0) r.mobId = guess;
                    }
                    if (r.mobId >= 0 && r.mobMap < 0 && mapId > 1 && isMobTemplateOnMap(ref, r.mobId)) {
                        r.mobMap = mapId;
                    }
                    if (r.lastCurSum >= 0 && pi[2] > r.lastCurSum && !r.progressRecorded) {
                        r.progressRecorded = true;
                        if (mapId > 1) r.mobMap = mapId;
                        r.spotX = px;
                        r.spotY = py;
                    }
                    r.lastCurSum = pi[2];
                    if ((qType == 2 || qType == 3) && r.turnMap < 0 && npcHere) {
                        r.turnMap = mapId;
                        r.turnNpc = npcId;
                    }
                } else {
                    r.seenFinish = true;
                    if (r.seq < 0) r.seq = ++recordSeq;
                    if (r.minLevel < 0) r.minLevel = lv;
                    if (r.turnMap < 0 && npcHere) {
                        r.turnMap = mapId;
                        r.turnNpc = npcId;
                    }
                }
            }
        } catch (Throwable t) {
            logReflectErrorOnce("recordScanVector", t);
        }
    }

    private static String buildRecordedJson() {
        List<QStep> list = new ArrayList<>();
        for (QStep r : recordedQuests.values()) {
            if (r.seq >= 0) list.add(r);
        }
        Collections.sort(list, (a, b) -> Integer.compare(a.seq, b.seq));
        StringBuilder sb = new StringBuilder("{\"version\":1,\"steps\":[\n");
        for (int i = 0; i < list.size(); i++) {
            sb.append("  ").append(stepToJson(list.get(i)));
            if (i < list.size() - 1) sb.append(",");
            sb.append("\n");
        }
        sb.append("]}\n");
        return sb.toString();
    }

    private static void flushRecordedQuests(boolean force) {
        try {
            long now = System.currentTimeMillis();
            if (!force && now - lastRecordWriteMs < 2500L) return;
            String json = buildRecordedJson();
            if (!force && json.equals(lastRecordJson)) return;
            lastRecordWriteMs = now;
            lastRecordJson = json;
            writeTextFileAtomic(questRecordedFile(), json);
        } catch (Throwable t) {
            logReflectErrorOnce("flushRecordedQuests", t);
        }
    }

    /** G\u1ecdi trong v\u00f2ng l\u1eb7p (d\u01b0\u1edbi TICK_LOCK), k\u1ec3 c\u1ea3 khi AUTO_QUEST \u0111ang t\u1eaft. */
    private static void recordQuestSnapshot() {
        long now = System.currentTimeMillis();
        if (now - lastRecordTickMs < 600L) return;
        lastRecordTickMs = now;
        try {
            ClassLoader cl = getGameClassLoader();
            if (cl == null) return;
            RefCache ref = getRef(cl);
            if (ref == null || ref.clsQuest == null || ref.clsGameScr == null) return;
            Object player = (ref.fPlayer != null) ? ref.fPlayer.get(null) : null;
            int lv = getPlayerLevel(ref, player);
            if (player == null || lv < 1) return;
            int mapId = getCurrentMapId(ref);
            if (mapId < 0) return;
            int px = ref.fX.getInt(player);
            int py = ref.fY.getInt(player);

            Set<Integer> presentNow = new HashSet<>();
            recordScanVector(ref, ref.fVecNew.get(null), 0, mapId, lv, px, py, presentNow);
            recordScanVector(ref, ref.fVecDoingMain.get(null), 1, mapId, lv, px, py, presentNow);
            recordScanVector(ref, ref.fVecDoingSub.get(null), 1, mapId, lv, px, py, presentNow);
            recordScanVector(ref, ref.fVecFinish.get(null), 2, mapId, lv, px, py, presentNow);

            // \u0110\u00e3 th\u1ea5y \u1edf "m\u1edbi" r\u1ed3i bi\u1ebfn m\u1ea5t kh\u1ecfi m\u1ecdi danh s\u00e1ch -> \u0111\u00e3 nh\u1eadn & l\u00e0m xong r\u1ea5t nhanh -> v\u1eabn ghi v\u00e0o chu\u1ed7i
            for (QStep r : recordedQuests.values()) {
                if (r.seq < 0 && r.seenNew && !presentNow.contains(r.questId)) {
                    r.seq = ++recordSeq;
                    if (r.minLevel < 0) r.minLevel = lv;
                }
            }
            flushRecordedQuests(false);
        } catch (Throwable t) {
            logReflectErrorOnce("recordQuestSnapshot", t);
        }
    }

    private static void handleRecordCommand(String[] parts) {
        String sub = parts.length > 1 ? parts[1].trim().toLowerCase() : "status";
        switch (sub) {
            case "1": case "on": case "start":
                questRecordEnabled = true;
                startAutoQuestLoop();
                System.err.println("[RECORDER] B\u1eadt ghi nhi\u1ec7m v\u1ee5 -> " + questRecordedFile().getAbsolutePath());
                break;
            case "0": case "off": case "stop":
                questRecordEnabled = false;
                synchronized (TICK_LOCK) { flushRecordedQuests(true); }
                System.err.println("[RECORDER] T\u1eaft ghi, \u0111\u00e3 l\u01b0u " + recordedQuests.size() + " nhi\u1ec7m v\u1ee5 theo d\u00f5i.");
                break;
            case "save":
                synchronized (TICK_LOCK) { flushRecordedQuests(true); }
                System.err.println("[RECORDER] \u0110\u00e3 l\u01b0u file: " + questRecordedFile().getAbsolutePath());
                break;
            case "clear":
                synchronized (TICK_LOCK) {
                    recordedQuests.clear();
                    recordSeq = 0;
                    lastRecordJson = "";
                }
                System.err.println("[RECORDER] \u0110\u00e3 x\u00f3a d\u1eef li\u1ec7u ghi trong b\u1ed9 nh\u1edb (file gi\u1eef nguy\u00ean cho t\u1edbi l\u1ea7n ghi k\u1ebf).");
                break;
            default:
                System.err.println("[RECORDER] record=" + questRecordEnabled + " | theo d\u00f5i=" + recordedQuests.size()
                        + " | file=" + questRecordedFile().getAbsolutePath());
        }
    }

    private static boolean isIgnoredNpcOrTinhTuQuest(RefCache ref, Object quest) {
        if (quest == null || ref == null) return true;
        try {
            int npcId = ref.fQNpcId.getInt(quest);
            if (ignoredNpcIds.contains(npcId)) return true;

            String qName = (String) ref.fQName.get(quest);
            String qShort = (String) ref.fQShort.get(quest);
            String qHelp = (String) ref.fQHelp.get(quest);

            String normName = normalizeText(ref, qName);
            String normAll = normalizeText(ref,
                    (qName != null ? qName : "") + " " + (qShort != null ? qShort : "") + " " + (qHelp != null ? qHelp : ""));
                // Bo qua NV hang ngay cap thap o Lang 1 khi nhan vat da len chuong 2 (Lv >= 35)
            if (lastKnownPlayerLevel >= 35 && (normName.contains("nvhangngay") || normAll.contains("nvhangngay"))) {
                return true;
            }
            if (normName.startsWith("nvtt") || normAll.contains("nvtt")
                    || normAll.contains("tinhtu") || normAll.contains("missanna")
                    || normAll.contains("dosat")) {
                if (normName.startsWith("nvtt") || normAll.contains("tinhtu") || normAll.contains("missanna")) {
                    ignoredNpcIds.add(npcId);
                }
                return true;
            }

            Object npcObj = findNpcOnCurrentMap(ref, npcId);
            if (npcObj != null) {
                String npcName = normalizeText(ref, (String) ref.fObjName.get(npcObj));
                if (npcName.contains("missanna") || npcName.equals("anna") || npcName.contains("tinhtu")) {
                    ignoredNpcIds.add(npcId);
                    return true;
                }
            }

            Object targetsObj = ref.fQTargets.get(quest);
            if (targetsObj != null && targetsObj.getClass().isArray()) {
                int len = Array.getLength(targetsObj);
                for (int i = 0; i < len; i++) {
                    int tId = ((Number) Array.get(targetsObj, i)).intValue();
                    if (tId >= 30000) return true;
                }
            }
        } catch (Throwable ignored) {}
        return false;
    }

    private static boolean isQuestInVector(RefCache ref, Object mVec, int targetQId) {
        if (mVec == null || ref == null) return false;
        try {
            Method[] vm = getVectorMethods(mVec);
            if (vm == null) return false;
            int size = (Integer) invokeMethod(vm[0], mVec);
            for (int i = 0; i < size; i++) {
                Object q = invokeMethod(vm[1], mVec, i);
                if (q != null && ref.clsQuest.isInstance(q) && ref.fQId.getInt(q) == targetQId) {
                    return true;
                }
            }
        } catch (Throwable ignored) {}
        return false;
    }

    private static Object pickQuestFromVector(
            RefCache ref, Object mVec, String filter, boolean skipDeferredQuests) {
        if (mVec == null || ref == null) return null;
        try {
            Method[] vm = getVectorMethods(mVec);
            if (vm == null) return null;

            long now = System.currentTimeMillis();
            int size = (Integer) invokeMethod(vm[0], mVec);
            for (int i = 0; i < size; i++) {
                Object q = invokeMethod(vm[1], mVec, i);
                if (q == null || !ref.clsQuest.isInstance(q)) continue;
                if (isIgnoredNpcOrTinhTuQuest(ref, q)) continue;

                int qId = ref.fQId.getInt(q);
                if (skipDeferredQuests) {
                    Long until = deferredQuestUntilMs.get(qId);
                    if (until != null && now < until) continue;
                }

                boolean isMain = ref.fQIsMain.getBoolean(q);
                if ("main".equalsIgnoreCase(filter) && !isMain) continue;
                if ("sub".equalsIgnoreCase(filter) && isMain) continue;
                return q;
            }
        } catch (Throwable ignored) {}
        return null;
    }

    /**
     * Chon nhiem vu trong vector ma nhan vat da du cap do (duoi quai can danh toi da 1 Lv) de lam ngay
     */
    private static Object pickReadyQuestFromVector(
            RefCache ref, Object mVec, String filter, int playerLevel, int currentMapId) {
        if (mVec == null || ref == null) return null;
        try {
            Method[] vm = getVectorMethods(mVec);
            if (vm == null) return null;

            int size = (Integer) invokeMethod(vm[0], mVec);
            for (int i = 0; i < size; i++) {
                Object q = invokeMethod(vm[1], mVec, i);
                if (q == null || !ref.clsQuest.isInstance(q)) continue;
                if (isIgnoredNpcOrTinhTuQuest(ref, q)) continue;

                boolean isMain = ref.fQIsMain.getBoolean(q);
                if ("main".equalsIgnoreCase(filter) && !isMain) continue;
                if ("sub".equalsIgnoreCase(filter) && isMain) continue;

                if (isActiveQuestReadyForPlayerLevel(ref, q, playerLevel, currentMapId)) {
                    return q;
                }
            }
        } catch (Throwable ignored) {}
        return null;
    }

    // =========================================================================
    // P1-6: NHAN / TRA NHIEM VU KHONG SLEEP DAI, XAC NHAN VECTOR THAT SU DOI
    // =========================================================================
    private static void handleNpcQuestInteraction(
            RefCache ref, Object quest, byte actionType, String tag, Object player) {
        try {
            int qId = ref.fQId.getInt(quest);
            boolean isMain = ref.fQIsMain.getBoolean(quest);
            String qName = (String) ref.fQName.get(quest);
            int npcId = ref.fQNpcId.getInt(quest);
            int altNpcId = npcId;
            if (ref.fQNpcAlt != null) {
                try {
                    altNpcId = ((Number) ref.fQNpcAlt.get(quest)).intValue();
                } catch (Throwable ignored) {}
            }

            int currentMapId = getCurrentMapId(ref);
            currentAqStateLabel = (actionType == 0) ? "ACCEPT_QUEST" : "TURN_IN_QUEST";
            currentAqDetailLabel = tag + ": " + qName + " (ID=" + qId + ", NPC=" + npcId + ")";

            if (trackedQuestId != qId || trackedTargetMobId != npcId) {
                trackedQuestId = qId;
                trackedTargetMobId = npcId;
                lockedTargetMob = null;
                searchedMissingNpcMaps.clear();
            }

            // Khi tra/doi thoai nhiem vu (actionType == 1) va co altNpcId (n0048) khac 0 -> Uu tien tim altNpcId hoac npcId
            Object npcObj = findNpcOnCurrentMap(ref, npcId);
            if (npcObj == null && altNpcId != npcId && altNpcId != 0) {
                npcObj = findNpcOnCurrentMap(ref, altNpcId);
                if (npcObj != null) npcId = altNpcId;
            }

            if (npcObj != null) {
                if (currentMapId >= 0) {
                    // [FIX 3A] Khong ghi de vi tri cua NPC xuat hien o nhieu Map theo chuong cot truyen (-7, -19)
                    if (!isTownMainNpc(npcId) && npcId != -7 && npcId != -19 && !knownNpcMap.containsKey(npcId)) {
                        knownNpcMap.put(npcId, currentMapId);
                        knowledgeDirty = true;
                    }
                    searchedMissingNpcMaps.remove(currentMapId);
                    if (actionType == 0) {
                        Integer prevOrig = questOriginMap.put(qId, currentMapId);
                        if (prevOrig == null || prevOrig != currentMapId) knowledgeDirty = true;
                    }
                }

                int px = ref.fX.getInt(player);
                int py = ref.fY.getInt(player);
                int nx = ref.fX.getInt(npcObj);
                int ny = ref.fY.getInt(npcObj);
                int distToNpc = (int) Math.hypot(nx - px, ny - py);

                if (distToNpc > 48) {
                    movePlayerCloseToTarget(ref, npcObj, player, currentMapId);
                    return;
                }

                if (currentRoutingTargetMap >= 0) {
                    stopNavOnly(ref);
                    currentRoutingTargetMap = -1;
                }
                releaseAllDirectionalKeys();
                ref.fFocus.set(null, npcObj);

                // Gian cach moi lan gui tuong tac NPC it nhat 1200ms
                long now = System.currentTimeMillis();
                Long lastInteract = questLastInteractTime.get(qId);
                if (lastInteract != null && (now - lastInteract < 1200L)) {
                    tapKeyHelper(-5, 25, 40);
                    return;
                }
                questLastInteractTime.put(qId, now);

                // [B5, B6] Tach rieng bo dem Nhan (actionType==0) va Tra (actionType==1)
                Map<Integer, Integer> failMap = (actionType == 0) ? questAcceptFailCounts : questTurnInFailCounts;
                int tries = failMap.getOrDefault(qId, 0) + 1;
                failMap.put(qId, tries);

                // Luan phien giua mo hoi thoai chuan cua n0092 va gui goi truc tiep qua n0044
                try {
                    if (tries % 2 == 1 && ref.mQStartDialog != null && ref.mQPerformStep != null) {
                        invokeMethod(ref.mQStartDialog, quest);
                        invokeMethod(ref.mQPerformStep, quest, 1, 0);
                    }
                } catch (Throwable ignored) {}

                try {
                    if (ref.mGetSvc != null && ref.mSendQuestPacket != null) {
                        Object svc = invokeMethod(ref.mGetSvc, null);
                        if (svc != null) {
                            invokeMethod(ref.mSendQuestPacket, svc, (short) qId, (byte) (isMain ? 0 : 1), actionType);
                        }
                    }
                } catch (Throwable ignored) {}

                tapKeyHelper(-6, 30, 60);
                tapKeyHelper(-5, 30, 60);

                logRateLimited("NPC_INTERACT_" + qId, 5000L,
                        "[AUTO-QUEST] " + tag + " (l\u1ea7n th\u1eed #" + tries + "): '" + qName
                                + "' (ID=" + qId + ", NPC=" + npcId + " t\u1ea1i Map " + currentMapId
                                + ", kho\u1ea3ng c\u00e1ch=" + distToNpc + "px)");

                if (actionType == 0 && tries >= 5) {
                    Object vecMain = ref.fVecDoingMain.get(null);
                    Object vecSub = ref.fVecDoingSub.get(null);
                    boolean movedToDoing = isQuestInVector(ref, vecMain, qId) || isQuestInVector(ref, vecSub, qId);
                    if (!movedToDoing) {
                        deferredQuestUntilMs.put(qId, now + 120000L);
                        questAcceptFailCounts.remove(qId);
                        tapKeyHelper(-7, 30, 50);
                        System.err.println("[AUTO-QUEST] Nhi\u1ec7m v\u1ee5 '" + qName + "' (ID=" + qId
                                + ") ch\u01b0a chuy\u1ec3n sang danh s\u00e1ch \u0110ang l\u00e0m sau " + tries
                                + " l\u1ea7n -> T\u1ea1m ho\u00e3n 120s \u0111\u1ec3 \u0111i C\u00e0y Level!");
                    } else {
                        questAcceptFailCounts.remove(qId);
                    }
                } else if (actionType == 1 && tries >= 5) {
                    Object vecFin = ref.fVecFinish.get(null);
                    boolean stillInFinish = isQuestInVector(ref, vecFin, qId);
                    if (stillInFinish) {
                        deferredQuestUntilMs.put(qId, now + 120000L);
                        questTurnInFailCounts.remove(qId);
                        if (autoSellEnabled && lastObservedBagCount >= Math.max(1, lastObservedMaxBag - 4)) {
                            autoSellNowRequested = true;
                        }
                        tapKeyHelper(-7, 30, 50);
                        System.err.println("[AUTO-QUEST][B5] Tr\u1ea3 NV '" + qName + "' (ID=" + qId
                                + ") th\u1ea5t b\u1ea1i sau " + tries
                                + " l\u1ea7n -> K\u00edch ho\u1ea1t ki\u1ec3m tra d\u1ecdn t\u00fai & t\u1ea1m ho\u00e3n 120s!");
                    } else {
                        questTurnInFailCounts.remove(qId);
                        questLevelWhenDied.remove(qId);
                        questDeathCounts.remove(qId);
                    }
                }
            } else {
                // [FIX 3B] Neu da tim qua >= 3 Map ma van khong thay NPC -> Tam hoan 90s de khong treo bot
                if (!questScriptMode && searchedMissingNpcMaps.size() >= 3) {
                    deferredQuestUntilMs.put(qId, System.currentTimeMillis() + 90000L);
                    searchedMissingNpcMaps.clear();
                    System.err.println("[AUTO-QUEST] Kh\u00f4ng t\u00ecm th\u1ea5y NPC (ID=" + npcId + ") sau 3 Map -> T\u1ea1m ho\u00e3n NV '"
                            + qName + "' 90s \u0111\u1ec3 l\u00e0m NV kh\u00e1c!");
                    return;
                }

                int npcMapId = resolveNpcReportMapId(ref, quest, npcId, qId, currentMapId);
                int playerLevel = getPlayerLevel(ref, player);
                int reqMapLv = getMinLevelForSpecialMap(npcMapId);

                // Neu Map cua NPC yeu cau Level cao hon Level hien tai -> Tam hoan nhiem vu nay va lap tuc di cay Level!
                if (reqMapLv > 0 && playerLevel > 0 && playerLevel < reqMapLv) {
                    deferredQuestUntilMs.put(qId, System.currentTimeMillis() + 300000L);
                    lockedTargetMob = null;
                    int mobCountOnMap = recordActorsOnCurrentMap(ref, player, currentMapId);
                    logRateLimited("NPC_MAP_LV_GATE_" + qId, 5000L,
                            "[AUTO-QUEST] Map " + npcMapId + " (NPC ID=" + npcId + " cho NV '" + qName
                                    + "') y\u00eau c\u1ea7u Lv." + reqMapLv + " m\u1edbi qua \u0111\u01b0\u1ee3c (Hi\u1ec7n t\u1ea1i: Lv."
                                    + playerLevel + ") -> Chuy\u1ec3n sang C\u00e0y Level l\u00ean Lv." + reqMapLv + "!");
                    executeAutoLevelFarming(ref, player, currentMapId, mobCountOnMap, playerLevel,
                            "C\u00e0y l\u00ean Lv." + reqMapLv + " \u0111\u1ec3 m\u1edf Map " + npcMapId + " (" + qName + ")");
                    return;
                }

                logRateLimited("FIND_NPC_" + npcId, 5000L,
                        "[AUTO-QUEST] T\u00ecm NPC (ID=" + npcId + ") cho '" + qName
                                + "' (" + tag + ") -> \u0110\u1ecbnh tuy\u1ebfn t\u1edbi Map " + npcMapId
                                + " (Hi\u1ec7n t\u1ea1i: Map " + currentMapId + ")");
                triggerAutoRouteToQuest(ref, quest, player, npcMapId, 0);
            }
        } catch (Throwable e) {
            logReflectErrorOnce("handleNpcQuestInteraction", e);
        }
    }

    // =========================================================================
    // P1-5 & P1-7: THUC THI NHIEM VU DU MOI LOAI & KIEM TRA LEVEL QUAI TRUOC KHI LAM
    // =========================================================================
        private static void executeActiveQuestStep(
            RefCache ref, Object quest, Object player,
            int currentMapId, int mobCountOnMap, int playerLevel) {
        try {
            int qId = ref.fQId.getInt(quest);
            boolean isMain = ref.fQIsMain.getBoolean(quest);
            String qName = (String) ref.fQName.get(quest);
            String qShort = (String) ref.fQShort.get(quest);
            int npcId = ref.fQNpcId.getInt(quest);
            int qType = ((Number) ref.fQType.get(quest)).intValue();
            Object targetsObj = ref.fQTargets.get(quest);
            Object maxCountsObj = ref.fQMaxCounts.get(quest);
            Object curCountsObj = ref.fQCurCounts.get(quest);

            questAcceptFailCounts.remove(qId);
            deferredQuestUntilMs.remove(qId);

            Integer knownMap = knownNpcMap.get(npcId);
            if (knownMap != null && !questOriginMap.containsKey(qId)) {
                questOriginMap.put(qId, knownMap);
                knowledgeDirty = true;
            }

            if (qType == 2 || qType == 3) {
                handleNpcQuestInteraction(ref, quest, (byte) 1, "\u0110\u1ed1i tho\u1ea1i NV", player);
                return;
            }

            String combinedQuestText = extractAllTextFromQuest(ref, quest);

            int targetId = -1;
            int curProgress = 0;
            int maxProgress = 0;
            int totalCurProgress = 0;
            int totalMaxProgress = 0;
            boolean hasTargetArray = false;
            boolean allDone = false;

            Set<Integer> incompleteTargetIds = new HashSet<>();
            Set<Integer> completedTargetIds = new HashSet<>();
            Map<Integer, String> targetProgressLabels = new ConcurrentHashMap<>();

            if (targetsObj != null && maxCountsObj != null && curCountsObj != null) {
                int len = Math.min(Array.getLength(targetsObj),
                        Math.min(Array.getLength(maxCountsObj), Array.getLength(curCountsObj)));
                if (len > 0) {
                    hasTargetArray = true;
                    allDone = true;
                    for (int i = 0; i < len; i++) {
                        int cur = ((Number) Array.get(curCountsObj, i)).intValue();
                        int max = ((Number) Array.get(maxCountsObj, i)).intValue();
                        int tId = ((Number) Array.get(targetsObj, i)).intValue();
                        if (max > 0) {
                            totalCurProgress += Math.min(cur, max);
                            totalMaxProgress += max;
                        }
                        if (tId >= 0) {
                            targetProgressLabels.put(tId, cur + "/" + max);
                        }
                        if (max <= 0 || cur < max) {
                            allDone = false;
                            if (qType == 1 && tId >= 0) {
                                if (!exhaustedQuestTargets.contains(currentMapId + ":" + tId)
                                        && !exhaustedQuestTargets.contains("*:" + tId)) {
                                    incompleteTargetIds.add(tId);
                                }
                            }
                            if (targetId < 0) {
                                targetId = tId;
                                curProgress = cur;
                                maxProgress = max;
                            }
                        } else if (qType == 1 && tId >= 0) {
                            completedTargetIds.add(tId);
                        }
                    }
                    if (!allDone && qType == 1 && incompleteTargetIds.isEmpty() && targetId >= 0) {
                        exhaustedQuestTargets.clear();
                        incompleteTargetIds.add(targetId);
                    }
                }
            }

            if (hasTargetArray && allDone) {
                lockedTargetMob = null;
                exhaustedQuestTargets.clear();
                questLevelWhenDied.remove(qId);
                questDeathCounts.remove(qId);
                handleNpcQuestInteraction(ref, quest, (byte) 1, "Ho\u00e0n t\u1ea5t NV", player);
                return;
            }

            Set<Integer> textCandidateMobIds = inferAllMonsterIdsFromQuestText(ref, quest, combinedQuestText);
            if (qType != 1 || incompleteTargetIds.isEmpty()) {
                for (int mId : textCandidateMobIds) {
                    if (!completedTargetIds.contains(mId)
                            && !exhaustedQuestTargets.contains(currentMapId + ":" + mId)
                            && !exhaustedQuestTargets.contains("*:" + mId)) {
                        incompleteTargetIds.add(mId);
                    }
                }
            }

            int chosenEffectiveMobId = (qType == 1 && targetId >= 0)
                    ? targetId : inferMonsterIdFromQuestText(ref, quest, combinedQuestText);
            if (incompleteTargetIds.size() > 1 && currentMapId > 1) {
                for (int candId : incompleteTargetIds) {
                    String candName = getMonsterTemplateName(ref, candId);
                    if (isTargetMobPresentOnCurrentMap(ref, candId, normalizeText(ref, candName), "", currentMapId)) {
                        chosenEffectiveMobId = candId;
                        break;
                    }
                }
            } else if (!incompleteTargetIds.isEmpty() && !incompleteTargetIds.contains(chosenEffectiveMobId)) {
                chosenEffectiveMobId = incompleteTargetIds.iterator().next();
            }

            int effectiveMobId = chosenEffectiveMobId;
            ensureQuestScriptLoaded();
            QStep fallbackStep = questScriptByQuestId.get(qId);
            // [FIX 4] Ghi de quai va Map theo kich ban cho MOI loai nhiem vu (ke ca qType == 1)
            boolean scriptOv = (questScriptMode && scriptOverrideQuestId == qId);
            int stepMobId = scriptOv ? scriptOverrideMobId : (fallbackStep != null ? fallbackStep.mobId : -1);
            int stepMapId = scriptOv ? scriptOverrideMapId : (fallbackStep != null ? fallbackStep.mobMap : -1);
            if (stepMobId >= 0) {
                if (qType != 1 || incompleteTargetIds.isEmpty()) {
                    effectiveMobId = stepMobId;
                    incompleteTargetIds.clear();
                    incompleteTargetIds.add(effectiveMobId);
                } else if (incompleteTargetIds.contains(stepMobId)) {
                    // NV danh nhieu loai quai: chi doi quai uu tien, GIU NGUYEN cac muc tieu chua xong khac
                    effectiveMobId = stepMobId;
                }
            }
            boolean isBossQuest = (qType == 1 && maxProgress == 1 && incompleteTargetIds.size() <= 1);
            int questTargetMapId = (stepMapId >= 0 && !searchedEmptyMaps.contains(stepMapId))
                    ? stepMapId
                    : resolveQuestTargetMapId(ref, quest, effectiveMobId, maxProgress, currentMapId);
            String targetMobName = getMonsterTemplateName(ref, effectiveMobId);

            int questMobLevel = resolveQuestMobLevel(ref, effectiveMobId, questTargetMapId, isBossQuest);
            int requiredPlayerLevel = getRequiredPlayerLevelForQuest(ref, qId, effectiveMobId, questTargetMapId, isBossQuest);

            if (playerLevel < requiredPlayerLevel && !(scriptOv && scriptOverrideMinLv >= 0)) {
                String mobDesc = (!targetMobName.isEmpty() ? targetMobName : ("Qu\u00e1i Map " + questTargetMapId))
                        + (questMobLevel > 0 ? (" Lv." + questMobLevel) : "");
                logRateLimited("QUEST_WAIT_MOB_LV_" + qId, 5000L,
                        "[AUTO-QUEST] Qu\u00e1i nhi\u1ec7m v\u1ee5 '" + mobDesc
                                + "' cao h\u01a1n Lv hi\u1ec7n t\u1ea1i (Lv." + playerLevel
                                + ") -> B\u1eadt \u0111\u00e1nh qu\u00e1i theo Lv \u0111\u1ebfn khi \u0111\u1ea1t Lv."
                                + requiredPlayerLevel
                                + " (d\u01b0\u1edbi qu\u00e1i 1 Lv) m\u1edbi b\u1eaft \u0111\u1ea7u l\u00e0m NV '"
                                + qName + "'!");
                executeAutoLevelFarming(ref, player, currentMapId, mobCountOnMap, playerLevel,
                        "C\u00e0y l\u00ean Lv." + requiredPlayerLevel + " (\u0111\u1ec3 d\u01b0\u1edbi " + mobDesc
                                + " 1 Lv) cho NV " + qName);
                return;
            }
            if (modNativeLevelFarmActive) {
                setModNativeLevelFarm(ref, false, playerLevel);
                System.err.println("[AUTO-QUEST] \u0110\u00e3 \u0111\u1ee7 Level l\u00e0m nhi\u1ec7m v\u1ee5 -> T\u1eaft Auto-Farm Lv c\u1ee7a Mod \u0111\u1ec3 ti\u1ebfp t\u1ee5c l\u00e0m NV!");
            }

            int trackingTargetKey = (effectiveMobId >= 0) ? effectiveMobId : targetId;
            if (trackedQuestId != qId || trackedTargetMobId != trackingTargetKey) {
                if (trackedQuestId != qId) {
                    currentTripGoalMap = -1;
                    resetLockedMonsterSpot();
                }
                trackedQuestId = qId;
                trackedTargetMobId = trackingTargetKey;
                lockedTargetMob = null;
                approachTrackedMob = null;
                searchedEmptyMaps.clear();
                searchedEmptyUntilMs.clear();
                lastTargetSeenOrQuestChangeTime = System.currentTimeMillis();
            }

            currentAqStateLabel = "DOING_QUEST";
            currentAqDetailLabel = (isMain ? "[Ch\u00ednh] " : "[Ph\u1ee5] ") + qName
                    + " (" + (totalMaxProgress > 0 ? (totalCurProgress + "/" + totalMaxProgress) : (curProgress + "/" + maxProgress)) + ")";

            if (lastLoggedQuestId != qId) {
                lastLoggedQuestId = qId;
                String targetLabel = (qType == 1)
                        ? ("Qu\u00e1i ID=" + effectiveMobId + (targetMobName.isEmpty() ? "" : " (" + targetMobName + ")")
                                + (incompleteTargetIds.size() > 1 ? (" | \u0110a m\u1ee5c ti\u00eau=" + incompleteTargetIds) : ""))
                        : ("V\u1eadt ph\u1ea9m ID=" + targetId + (targetMobName.isEmpty() ? "" : " | S\u0103n: " + targetMobName + " (ID=" + effectiveMobId + ")"));
                System.err.println("[AUTO-QUEST] B\u1eaft \u0111\u1ea7u b\u01b0\u1edbc " + (isMain ? "NV Ch\u00ednh" : "NV Ph\u1ee5") + ": " + qName
                        + " (ID=" + qId + ") | Lo\u1ea1i=" + qType + " | " + targetLabel
                        + (questMobLevel > 0 ? (" | Lv Qu\u00e1i=" + questMobLevel) : "")
                        + " | Ti\u1ebfn \u0111\u1ed9=" + formatQuestProgressArrays(ref, quest)
                        + " | Map \u0111\u00edch=" + questTargetMapId
                        + " | M\u00f4 t\u1ea3: " + (qShort != null ? qShort : ""));
            }

            if (questTargetMapId >= 0 && currentMapId != questTargetMapId) {
                lockedTargetMob = null;
                triggerAutoRouteToQuest(ref, quest, player, questTargetMapId, mobCountOnMap);
                return;
            }

            if (System.currentTimeMillis() - currentMapEnterTime < 300L) return;

            String normShort = normalizeText(ref, combinedQuestText);
            String normTargetName = normalizeText(ref, targetMobName);
            Set<String> incompleteNormNames = new HashSet<>();
            for (int incId : incompleteTargetIds) {
                String nm = normalizeText(ref, getMonsterTemplateName(ref, incId));
                if (!nm.isEmpty()) incompleteNormNames.add(nm);
            }

            Object vecActors = (ref.fVecActors != null) ? ref.fVecActors.get(null) : null;
            if (vecActors == null) return;
            Method[] vm = getVectorMethods(vecActors);
            if (vm == null) return;

            int px = ref.fX.getInt(player);
            int py = ref.fY.getInt(player);
            updatePositionProgress(px, py);

            long now = System.currentTimeMillis();
            synchronized (temporarilyIgnoredMobs) {
                temporarilyIgnoredMobs.entrySet().removeIf(e -> now >= e.getValue());
            }

            int count = (Integer) invokeMethod(vm[0], vecActors);

            List<int[]> allQuestMobCoords = new ArrayList<>();
            List<Object> alivePrimaryMobs = new ArrayList<>();
            List<Object> aliveSecondaryMobs = new ArrayList<>();
            List<Object> aliveFallbackMobs = new ArrayList<>();

            for (int i = 0; i < count; i++) {
                Object obj = invokeMethod(vm[1], vecActors, i);
                if (obj == null || obj == player) continue;

                byte objType = ref.fObjType.getByte(obj);
                if (!ref.clsMonsterBase.isInstance(obj) && objType != 1) continue;

                int mobTemplateId = -1;
                if (ref.clsMonsterBase.isInstance(obj)) {
                    mobTemplateId = ref.fMobTemplateId.getInt(obj);
                    if (mobTemplateId == 0 && currentMapId > 0) continue;
                }

                if (completedTargetIds.contains(mobTemplateId)
                        || exhaustedQuestTargets.contains(currentMapId + ":" + mobTemplateId)
                        || exhaustedQuestTargets.contains("*:" + mobTemplateId)) {
                    continue;
                }

                int mx = ref.fX.getInt(obj);
                int my = ref.fY.getInt(obj);
                String objName = (String) ref.fObjName.get(obj);
                if (objName != null && !objName.trim().isEmpty() && mobTemplateId >= 0) {
                    knownMobNameMap.put(mobTemplateId, objName.trim());
                    if (currentMapId > 1) {
                        knownMobMap.putIfAbsent(mobTemplateId, currentMapId);
                    }
                }
                String normObjName = normalizeText(ref, objName);

                boolean isPrimaryMatch = (effectiveMobId >= 0 && mobTemplateId == effectiveMobId)
                        || (!normTargetName.isEmpty() && normObjName.equals(normTargetName));

                boolean isSecondaryMatch = false;
                if (!isPrimaryMatch) {
                    if (!incompleteTargetIds.isEmpty()) {
                        isSecondaryMatch = incompleteTargetIds.contains(mobTemplateId)
                                || (!normObjName.isEmpty() && incompleteNormNames.contains(normObjName));
                    } else if (effectiveMobId < 0) {
                        isSecondaryMatch = (normObjName.length() >= 3 && normShort.contains(normObjName));
                    }
                }

                boolean isFallbackMatch = false;
                if (!isPrimaryMatch && !isSecondaryMatch
                        && (qType == 0 || qType > 3) && effectiveMobId < 0 && currentMapId == questTargetMapId) {
                    int mLv = getMonsterTemplateLevel(ref, mobTemplateId);
                    if (mLv <= 0 || mLv <= playerLevel + MAX_ALLOWED_MOB_LEVEL_GAP) {
                        isFallbackMatch = true;
                    }
                }

                if (isPrimaryMatch || isSecondaryMatch || isFallbackMatch) {
                    allQuestMobCoords.add(new int[]{mx, my});

                    boolean isAlive = !ref.fIsDead.getBoolean(obj)
                            && ref.fState.getInt(obj) != 4
                            && ref.fHp.getInt(obj) > 0
                            && !temporarilyIgnoredMobs.containsKey(obj);
                    if (isAlive) {
                        if (isPrimaryMatch) alivePrimaryMobs.add(obj);
                        else if (isSecondaryMatch) aliveSecondaryMobs.add(obj);
                        else aliveFallbackMobs.add(obj);
                    }
                }
            }

            if (allQuestMobCoords.isEmpty()) {
                triggerAutoRouteToQuest(ref, quest, player, questTargetMapId, mobCountOnMap);
                return;
            }

            lastTargetSeenOrQuestChangeTime = now;

            int[] bestSpot = resolveBestClusterSpotOnMap(ref, currentMapId, trackingTargetKey, allQuestMobCoords);
            int stepSpotX = scriptOv ? scriptOverrideSpotX : (fallbackStep != null ? fallbackStep.spotX : -1);
            int stepSpotY = scriptOv ? scriptOverrideSpotY : (fallbackStep != null ? fallbackStep.spotY : -1);
            if (stepSpotX >= 0 && stepSpotY >= 0 && currentMapId == questTargetMapId) {
                bestSpot = new int[]{stepSpotX, stepSpotY, Math.max(1, allQuestMobCoords.size())};
            }
            int spotX = (bestSpot != null) ? bestSpot[0] : px;
            int spotY = (bestSpot != null) ? bestSpot[1] : py;
            int distPlayerToSpot = (int) Math.hypot(spotX - px, spotY - py);

            if (distPlayerToSpot > 36) {
                lockedTargetMob = null;
                if (now - lastRouteTime > 2000L || currentRoutingTargetMap != currentMapId
                        || Math.hypot(spotX - currentRoutingTargetX, spotY - currentRoutingTargetY) > 20) {
                    lastRouteTime = now;
                    currentRoutingTargetMap = currentMapId;
                    currentRoutingTargetX = spotX;
                    currentRoutingTargetY = spotY;
                    startNavigatorRouteRequest(ref, currentMapId, true, spotX, spotY, 18, false,
                            "Di chuy\u1ec3n v\u1ec1 t\u00e2m b\u00e3i NV (" + spotX + "," + spotY + ")");
                } else {
                    tickNavigator(ref);
                }
                if (now - lastPositionChangeTime > 1500L) {
                    stepDirectionalKeyTowards(px, py, spotX, spotY, now - lastPositionChangeTime > 2800L);
                } else {
                    releaseAllDirectionalKeys();
                }
                return;
            }

            if (currentRoutingTargetMap >= 0) {
                stopNavOnly(ref);
                currentRoutingTargetMap = -1;
            }
            releaseAllDirectionalKeys();
            lastPositionChangeTime = now;
            if (checkAndAutoSwitchZoneIfCrowded(ref, player, currentMapId, spotX, spotY, isBossQuest)) {
                return;
            }

            if (qType == 0 && (now - lastPickupKeyTime > 700L)) {
                for (int i = 0; i < count; i++) {
                    Object obj = invokeMethod(vm[1], vecActors, i);
                    if (obj == null || obj == player) continue;
                    byte objType = ref.fObjType.getByte(obj);
                    if (objType == 3 || objType == 4) {
                        int d = (int) Math.hypot(ref.fX.getInt(obj) - px, ref.fY.getInt(obj) - py);
                        if (d <= 95) {
                            lastPickupKeyTime = now;
                            ref.fFocus.set(null, obj);
                            tapKeyHelper(-5, 25, 30);
                            break;
                        }
                    }
                }
            }

            Object chosenTarget = null;
            int chosenDist = Integer.MAX_VALUE;

            if (lockedTargetMob != null && temporarilyIgnoredMobs.containsKey(lockedTargetMob)) {
                lockedTargetMob = null;
            }

            if (lockedTargetMob != null && ref.clsMonsterBase.isInstance(lockedTargetMob)) {
                try {
                    int lockedId = ref.fMobTemplateId.getInt(lockedTargetMob);
                    int lmx = ref.fX.getInt(lockedTargetMob);
                    int lmy = ref.fY.getInt(lockedTargetMob);
                    boolean inSpot = Math.hypot(lmx - spotX, lmy - spotY) <= FARM_SPOT_RADIUS;
                    boolean isLockedCompleted = completedTargetIds.contains(lockedId)
                            || exhaustedQuestTargets.contains(currentMapId + ":" + lockedId)
                            || exhaustedQuestTargets.contains("*:" + lockedId)
                            || (qType == 1 && !incompleteTargetIds.isEmpty() && !incompleteTargetIds.contains(lockedId));
                    if (inSpot && !isLockedCompleted
                            && !ref.fIsDead.getBoolean(lockedTargetMob)
                            && ref.fState.getInt(lockedTargetMob) != 4
                            && ref.fHp.getInt(lockedTargetMob) > 0) {
                        chosenTarget = lockedTargetMob;
                        chosenDist = (int) Math.hypot(lmx - px, lmy - py);
                    } else {
                        lockedTargetMob = null;
                    }
                } catch (Throwable ignored) {
                    lockedTargetMob = null;
                }
            }

            if (chosenTarget == null) {
                List<List<Object>> priorityGroups = Arrays.asList(alivePrimaryMobs, aliveSecondaryMobs, aliveFallbackMobs);
                for (List<Object> group : priorityGroups) {
                    for (Object mob : group) {
                        int mx = ref.fX.getInt(mob);
                        int my = ref.fY.getInt(mob);
                        if (Math.hypot(mx - spotX, my - spotY) <= FARM_SPOT_RADIUS) {
                            int d = (int) Math.hypot(mx - px, my - py);
                            if (d < chosenDist) {
                                chosenDist = d;
                                chosenTarget = mob;
                            }
                        }
                    }
                    if (chosenTarget != null) break;
                }
                lockedTargetMob = chosenTarget;
            }

            if (chosenTarget != null) {
                int actualMobTplId = -1;
                int currentMobHp = 0;
                try { currentMobHp = ref.fHp.getInt(chosenTarget); } catch (Throwable ignored) {}

                if (ref.clsMonsterBase.isInstance(chosenTarget)) {
                    actualMobTplId = ref.fMobTemplateId.getInt(chosenTarget);
                    int actualMobLv = getMonsterTemplateLevel(ref, actualMobTplId);
                    if (actualMobLv > 1) {
                        knownMobLevelMap.put(actualMobTplId, actualMobLv);
                        observedQuestMobLevel.merge(qId, actualMobLv, Math::max);
                        knowledgeDirty = true;
                    }
                    if (actualMobLv > 1 && playerLevel < (actualMobLv - MAX_ALLOWED_MOB_LEVEL_GAP) && !(scriptOv && scriptOverrideMinLv >= 0)) {
                        lockedTargetMob = null;
                        int needLv = actualMobLv - MAX_ALLOWED_MOB_LEVEL_GAP;
                        String mName = (String) ref.fObjName.get(chosenTarget);
                        System.err.println("[AUTO-QUEST][B3] Ghi nh\u1edb Lv th\u1ef1c t\u1ebf c\u1ee7a qu\u00e1i '"
                                + (mName != null ? mName : "Qu\u00e1i") + "' l\u00e0 Lv." + actualMobLv
                                + " cho NV ID=" + qId + " -> C\u00e0y l\u00ean Lv." + needLv + "!");
                        executeAutoLevelFarming(ref, player, currentMapId, mobCountOnMap, playerLevel,
                                "C\u00e0y l\u00ean Lv." + needLv + " (d\u01b0\u1edbi qu\u00e1i " + (mName != null ? mName : "") + " Lv." + actualMobLv + " 1 Lv)");
                        return;
                    }
                }

                if (actualMobTplId >= 0 && currentMapId > 1) {
                    Integer prev = knownMobMap.putIfAbsent(actualMobTplId, currentMapId);
                    if (prev == null) knowledgeDirty = true;
                }

                ref.fFocus.set(null, chosenTarget);
                performFullSkillCombo(ref, chosenTarget);
                updateQuestProgressTracker(qId, effectiveMobId, qType, isBossQuest, playerLevel,
                        (totalMaxProgress > 0 ? totalCurProgress : curProgress), currentMapId, actualMobTplId, currentMobHp);

                String mName = (String) ref.fObjName.get(chosenTarget);
                if (mName == null || mName.isEmpty()) mName = targetMobName;
                String progStr = targetProgressLabels.getOrDefault(actualMobTplId, curProgress + "/" + maxProgress);
                currentAqDetailLabel = "\u0110\u1ee9ng c\u1eafm ch\u1ed1t (" + spotX + "," + spotY + ") -> \u0110\u00e1nh "
                        + mName + " " + progStr;
                logRateLimited("COMBAT_QUEST", 5000L,
                        "[AUTO-QUEST] \u0110\u1ee9ng c\u1eafm ch\u1ed1t (" + spotX + "," + spotY + ") -> \u0110ang \u0111\u00e1nh "
                                + mName + " (ID=" + actualMobTplId + ") | "
                                + (isMain ? "NV Ch\u00ednh" : "NV Ph\u1ee5") + ": " + qName
                                + " (" + progStr + " | T\u1ed5ng: " + formatQuestProgressArrays(ref, quest) + ")");
                dirty = true;
            }
        } catch (Throwable e) {
            logReflectErrorOnce("executeActiveQuestStep", e);
        }
    }

    // =========================================================================
    // P2-13 & MUC 5.3: BAO TRANG THAI AQ_STATUS:{json} VA CHAN DOAN AUTO_QUEST:diag
    // =========================================================================
    private static void sendAqStatusIfEnabled(
            RefCache ref, Object player, int currentMapId, int playerLevel, boolean force) {
        if (!aqStatusIpcEnabled || ipcOut == null) return;
        long now = System.currentTimeMillis();
        if (!force && (now - lastAqStatusSendTime < 2000L)) return;
        lastAqStatusSendTime = now;

        try {
            int px = (player != null && ref != null && ref.fX != null) ? ref.fX.getInt(player) : -1;
            int py = (player != null && ref != null && ref.fY != null) ? ref.fY.getInt(player) : -1;
            int hp = (player != null && ref != null && ref.fHp != null) ? ref.fHp.getInt(player) : 0;
            int maxHp = (player != null && ref != null && ref.fMaxHp != null) ? ref.fMaxHp.getInt(player) : 0;
            int mp = (player != null && ref != null && ref.fMp != null) ? ref.fMp.getInt(player) : 0;
            int maxMp = (player != null && ref != null && ref.fMaxMp != null) ? ref.fMaxMp.getInt(player) : 0;

            int scriptTotal = (questScript != null) ? questScript.size() : 0;
            String json = String.format(
                    "{\"enabled\":%b,\"filter\":\"%s\",\"state\":\"%s\",\"detail\":\"%s\","
                            + "\"lv\":%d,\"farmMobLv\":%d,\"map\":%d,\"mapId\":%d,\"x\":%d,\"y\":%d,\"hp\":%d,\"maxHp\":%d,\"mp\":%d,\"maxMp\":%d,"
                            + "\"questId\":%d,\"targetMap\":%d,\"nav\":\"%s\",\"steady\":%b,"
                            + "\"scriptRunning\":%b,\"scriptRecording\":%b,\"scriptCursor\":%d,\"scriptTotal\":%d,"
                            + "\"bagCount\":%d,\"maxBag\":%d,\"soldCount\":%d,"
                            + "\"autoSell\":{\"enabled\":%b,\"white\":%b,\"green\":%b,\"yellow\":%b,\"purple\":%b,\"orange\":%b,"
                            + "\"locked\":%b,\"unlocked\":%b,\"enhanced\":%b,\"threshold\":%d}}",
                    autoQuestEnabled, escapeJson(autoQuestFilter), escapeJson(currentAqStateLabel),
                    escapeJson(currentAqDetailLabel), playerLevel, currentSelectedFarmMobLv, currentMapId, currentMapId, px, py,
                    hp, maxHp, mp, maxMp, trackedQuestId, currentTripGoalMap,
                    escapeJson(lastNavResultMsg), maxLevelCompletedSteadyMode,
                    questScriptMode, questRecordEnabled, scriptIdx, scriptTotal,
                    lastObservedBagCount, lastObservedMaxBag, totalAutoSoldItemsCount,
                    autoSellEnabled, autoSellColorFlags[0], autoSellColorFlags[1], autoSellColorFlags[2],
                    autoSellColorFlags[3], autoSellColorFlags[4], autoSellLocked, autoSellUnlocked,
                    autoSellEnhanced, autoSellBagThreshold);

            synchronized (HeadlessJ2ME.class) {
                ipcOut.println("AQ_STATUS:" + json);
                ipcOut.flush();
            }
        } catch (Throwable ignored) {}
    }

    private static String escapeJson(String s) {
        if (s == null) return "";
        return s.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", " ").replace("\r", "");
    }

    /**
     * Lenh chan doan AUTO_QUEST:diag in toan bo trang thai thuc te ra stderr
     */
    private static void dumpAutoQuestDiagnostics() {
        synchronized (TICK_LOCK) {
            try {
                ClassLoader cl = getGameClassLoader();
                if (cl == null) {
                    System.err.println("[AQ-DIAG] ClassLoader ch\u01b0a s\u1eb5n s\u00e0ng.");
                    return;
                }
                RefCache ref = getRef(cl);
                if (ref == null) {
                    System.err.println("[AQ-DIAG] RefCache ch\u01b0a kh\u1edfi t\u1ea1o \u0111\u01b0\u1ee3c.");
                    return;
                }

                Object player = (ref.fPlayer != null) ? ref.fPlayer.get(null) : null;
                int lv = getPlayerLevel(ref, player);
                int mapId = getCurrentMapId(ref);
                int px = (player != null && ref.fX != null) ? ref.fX.getInt(player) : -1;
                int py = (player != null && ref.fY != null) ? ref.fY.getInt(player) : -1;
                int hp = (player != null && ref.fHp != null) ? ref.fHp.getInt(player) : -1;
                int maxHp = (player != null && ref.fMaxHp != null) ? ref.fMaxHp.getInt(player) : -1;
                int mp = (player != null && ref.fMp != null) ? ref.fMp.getInt(player) : -1;
                int maxMp = (player != null && ref.fMaxMp != null) ? ref.fMaxMp.getInt(player) : -1;

                Object token = ensureControlToken(ref);
                Object navStatus = null;
                if (ref.mGetNav != null && ref.mGetNavStatus != null) {
                    Object navInst = invokeMethod(ref.mGetNav, null);
                    if (navInst != null) navStatus = invokeMethod(ref.mGetNavStatus, navInst);
                }

                System.err.println("==================== [AUTO_QUEST:diag] ====================");
                System.err.println("1. Nh\u00e2n v\u1eadt: Lv=" + lv + " | Map=" + mapId + " | Pos=(" + px + "," + py + ")"
                        + " | HP=" + hp + "/" + maxHp + " | MP=" + mp + "/" + maxMp);
                System.err.println("2. \u0110i\u1ec1u khi\u1ec3n & \u0110i\u1ec1u h\u01b0\u1edbng: Token=" + (token != null ? token.toString() : "null")
                        + " (prio=" + currentControlPriority + ") | NavStatus(n0193.n0003)="
                        + describeObjectFields(navStatus) + " | LastNavRes=" + lastNavResultMsg);
                System.err.println("3. Tr\u1ea1ng th\u00e1i Auto: state=" + currentAqStateLabel + " | detail=" + currentAqDetailLabel
                        + " | trackedQuestId=" + trackedQuestId + " | goalMap=" + currentTripGoalMap
                        + " | outOfHP=" + outOfHpPotions + " | outOfMP=" + outOfMpPotions);

                dumpQuestVectorDiag(ref, "vecNew (n0041)", ref.fVecNew != null ? ref.fVecNew.get(null) : null);
                dumpQuestVectorDiag(ref, "vecFinish (n0042)", ref.fVecFinish != null ? ref.fVecFinish.get(null) : null);
                dumpQuestVectorDiag(ref, "vecDoingMain (n0043)", ref.fVecDoingMain != null ? ref.fVecDoingMain.get(null) : null);
                dumpQuestVectorDiag(ref, "vecDoingSub (n0044)", ref.fVecDoingSub != null ? ref.fVecDoingSub.get(null) : null);

                System.err.println("5. B\u1ed9 nh\u1edb \u0111\u00e3 h\u1ecdc: knownNpcMap=" + knownNpcMap
                        + " | knownMobMap=" + knownMobMap
                        + " | knownMobLevelMap=" + knownMobLevelMap
                        + " | questOriginMap=" + questOriginMap
                        + " | stones=" + mapsWithTeleportStone
                        + " | deferred=" + deferredQuestUntilMs.keySet());

                if (ref.fMapNames != null) {
                    String[] mapNames = (String[]) ref.fMapNames.get(null);
                    if (mapNames != null) {
                        StringBuilder sbMaps = new StringBuilder("6. T\u00ean Map (n0158.n0395): ");
                        for (int i = 0; i < mapNames.length; i++) {
                            if (mapNames[i] != null) sbMaps.append(i).append(":").append(mapNames[i]).append(", ");
                        }
                        System.err.println(sbMaps.toString());
                    } else {
                        System.err.println("6. T\u00ean Map (n0158.n0395): null (ch\u01b0a n\u1ea1p)");
                    }
                }

                Object vecActors = (ref.fVecActors != null) ? ref.fVecActors.get(null) : null;
                if (vecActors != null) {
                    Method[] vm = getVectorMethods(vecActors);
                    if (vm != null) {
                        int sz = (Integer) invokeMethod(vm[0], vecActors);
                        StringBuilder sbAct = new StringBuilder("7. Th\u1ef1c th\u1ec3 t\u1ea1i Map " + mapId + " (t\u1ed5ng=" + sz + "): ");
                        for (int i = 0; i < sz; i++) {
                            Object a = invokeMethod(vm[1], vecActors, i);
                            if (a == null) continue;
                            byte t = ref.fObjType.getByte(a);
                            int id = ref.fNpcId.getInt(a);
                            String nm = (String) ref.fObjName.get(a);
                            int ax = ref.fX.getInt(a);
                            int ay = ref.fY.getInt(a);
                            sbAct.append("[type=").append(t).append(",id=").append(id)
                                    .append(",name=").append(nm).append("@").append(ax).append(",").append(ay).append("] ");
                        }
                        System.err.println(sbAct.toString());
                    }
                }
                System.err.println("===========================================================");
            } catch (Throwable t) {
                System.err.println("[AQ-DIAG] L\u1ed7i khi xu\u1ea5t ch\u1ea9n \u0111o\u00e1n: " + t.toString());
            }
        }
    }

    private static void dumpQuestVectorDiag(RefCache ref, String label, Object vec) {
        try {
            if (vec == null) {
                System.err.println("4. " + label + ": null");
                return;
            }
            Method[] vm = getVectorMethods(vec);
            if (vm == null) return;
            int sz = (Integer) invokeMethod(vm[0], vec);
            StringBuilder sb = new StringBuilder("4. " + label + " (size=" + sz + "): ");
            for (int i = 0; i < sz; i++) {
                Object q = invokeMethod(vm[1], vec, i);
                if (q == null || !ref.clsQuest.isInstance(q)) continue;
                int id = ref.fQId.getInt(q);
                byte type = ref.fQType.getByte(q);
                boolean main = ref.fQIsMain.getBoolean(q);
                int npc = ref.fQNpcId.getInt(q);
                String name = (String) ref.fQName.get(q);
                String desc = (String) ref.fQShort.get(q);
                sb.append("{id=").append(id)
                        .append(",main=").append(main)
                        .append(",type=").append(type)
                        .append(",npc=").append(npc)
                        .append(",name='").append(name).append("'")
                        .append(",progress=").append(formatQuestProgressArrays(ref, q))
                        .append(",desc='").append(desc).append("'} ");
            }
            System.err.println(sb.toString());
        } catch (Throwable ignored) {}
    }

    private static String formatQuestProgressArrays(RefCache ref, Object q) {
        try {
            Object t = ref.fQTargets.get(q);
            Object c = ref.fQCurCounts.get(q);
            Object m = ref.fQMaxCounts.get(q);
            if (t == null || c == null || m == null) return "none";
            int len = Math.min(Array.getLength(t), Math.min(Array.getLength(c), Array.getLength(m)));
            StringBuilder sb = new StringBuilder("[");
            for (int i = 0; i < len; i++) {
                if (i > 0) sb.append(",");
                sb.append(Array.get(t, i)).append(":").append(Array.get(c, i)).append("/").append(Array.get(m, i));
            }
            sb.append("]");
            return sb.toString();
        } catch (Throwable ignored) {
            return "err";
        }
    }

    private static String describeObjectFields(Object obj) {
        if (obj == null) return "null";
        StringBuilder sb = new StringBuilder("{");
        try {
            Field[] fs = obj.getClass().getDeclaredFields();
            for (int i = 0; i < fs.length; i++) {
                Field f = fs[i];
                if (Modifier.isStatic(f.getModifiers())) continue;
                f.setAccessible(true);
                if (i > 0) sb.append(",");
                sb.append(f.getName()).append("=").append(f.get(obj));
            }
        } catch (Throwable ignored) {}
        sb.append("}");
        return sb.toString();
    }

    // =========================================================================
    // CAU HINH HIEN THI FREEJ2ME, RENDER FRAME & VONG DOC LENH IPC
    // =========================================================================
    private static void initDisplayAndConfig(int width, int height) {
        try {
            try {
                Field soundField = Mobile.class.getDeclaredField("sound");
                soundField.setAccessible(true);
                soundField.setBoolean(null, false);
            } catch (Throwable ignored) {}
            try {
                Method clearDisplay = Mobile.class.getDeclaredMethod("clearDisplay");
                clearDisplay.setAccessible(true);
                clearDisplay.invoke(null);
            } catch (Throwable ignored) {}
            Class<?> configClass = Class.forName("org.recompile.freej2me.Config");
            Object configObj = configClass.getDeclaredConstructor().newInstance();
            try {
                Field onChangeField = configClass.getDeclaredField("onChange");
                onChangeField.setAccessible(true);
                onChangeField.set(configObj, (Runnable) () -> dirty = true);
            } catch (Throwable ignored) {}
            Field mobileConfigField = Mobile.class.getDeclaredField("config");
            mobileConfigField.setAccessible(true);
            mobileConfigField.set(null, configObj);
        } catch (Throwable ignored) {}
    }

    @SuppressWarnings("unchecked")
    private static void postLoadConfigInit(int width, int height) {
        try {
            Field mobileConfigField = Mobile.class.getDeclaredField("config");
            mobileConfigField.setAccessible(true);
            Object configObj = mobileConfigField.get(null);
            if (configObj != null) {
                Method initMethod = configObj.getClass().getDeclaredMethod("init");
                initMethod.setAccessible(true);
                initMethod.invoke(configObj);
                Field settingsField = configObj.getClass().getDeclaredField("settings");
                settingsField.setAccessible(true);
                Object settingsObj = settingsField.get(configObj);
                if (settingsObj instanceof Map) {
                    Map<String, String> settings = (Map<String, String>) settingsObj;
                    settings.put("width", String.valueOf(width));
                    settings.put("height", String.valueOf(height));
                    settings.put("sound", "off");
                }
            }
            try {
                Method resize = MobilePlatform.class.getDeclaredMethod("resizeLCD", int.class, int.class);
                resize.setAccessible(true);
                resize.invoke(platform, width, height);
            } catch (Throwable ignored) {}
        } catch (Throwable ignored) {}
    }

    private static boolean invokeLoadJar(MobilePlatform mp, String jarUrl) throws Exception {
        try {
            Method m = MobilePlatform.class.getMethod("loadJar", String.class);
            return (Boolean) m.invoke(mp, jarUrl);
        } catch (NoSuchMethodException e) {
            Method m = MobilePlatform.class.getMethod("load", String.class);
            return (Boolean) m.invoke(mp, jarUrl);
        }
    }

    private static void renderLoop(int fps) {
        long interval = 1000L / Math.max(1, Math.min(fps, 30));
        long lastForcedSend = 0;

        while (running) {
            try {
                // Nếu đang tắt hình (Tối ưu RAM/CPU), ngừng chụp màn hình & ngủ dài hơn
                if (!renderEnabled) {
                    Thread.sleep(250);
                    continue;
                }

                long now = System.currentTimeMillis();
                if (dirty || (now - lastForcedSend > 500)) {
                    dirty = false;
                    lastForcedSend = now;
                    sendCurrentFrame(false);
                }
                Thread.sleep(interval);
            } catch (Exception ignored) {}
        }
    }

    private static synchronized void sendCurrentFrame() {
        sendCurrentFrame(false);
    }

    private static synchronized void sendCurrentFrame(boolean force) {
        try {
            if (!renderEnabled || platform == null) return;
            BufferedImage lcd = platform.getLCD();
            if (lcd == null) return;

            int w = lcd.getWidth();
            int h = lcd.getHeight();

            BufferedImage rgbImage = new BufferedImage(w, h, BufferedImage.TYPE_INT_RGB);
            Graphics2D g = rgbImage.createGraphics();
            g.drawImage(lcd, 0, 0, null);
            g.dispose();

            int[] currentPixels = ((DataBufferInt) rgbImage.getRaster().getDataBuffer()).getData();
            if (!force && lastPixels != null && lastWidth == w && lastHeight == h && Arrays.equals(currentPixels, lastPixels)) {
                return;
            }

            if (lastPixels == null || lastWidth != w || lastHeight != h) {
                lastPixels = new int[currentPixels.length];
            }
            System.arraycopy(currentPixels, 0, lastPixels, 0, currentPixels.length);
            lastWidth = w;
            lastHeight = h;

            ByteArrayOutputStream baos = new ByteArrayOutputStream(8192);
            ImageIO.write(rgbImage, "jpg", baos);
            String b64 = Base64.getEncoder().encodeToString(baos.toByteArray());

            // Gửi chuỗi Base64 thuần để khớp với bộ giải mã Buffer.from(b64, 'base64') của vmManager.js
            ipcOut.println("FRAME:" + b64);
            ipcOut.flush();
        } catch (Exception ignored) {}
    }

    private static void readInputLoop() {
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8))) {
            String line;
            while ((line = reader.readLine()) != null) {
                line = line.trim();
                if (line.isEmpty()) continue;

                try {
                    if (line.startsWith("KD:")) {
                        platform.keyPressed(Integer.parseInt(line.substring(3)));
                        dirty = true;
                    } else if (line.startsWith("KU:")) {
                        platform.keyReleased(Integer.parseInt(line.substring(3)));
                        dirty = true;
                    } else if (line.startsWith("KP:")) {
                        int key = Integer.parseInt(line.substring(3));
                        tapKeyHelper(key, 30, 0);
                    } else if (line.startsWith("TEXT:")) {
                        String text = line.substring(5);
                        boolean directSet = smartInjectTextToActiveField(text, -1);
                        if (!directSet) {
                            for (int i = 0; i < text.length(); i++) {
                                int code = (int) text.charAt(i);
                                tapKeyHelper(code, 20, 15);
                            }
                        }
                        dirty = true;
                    } else if (line.startsWith("PARAM:")) {
                        String rest = line.substring(6);
                        String[] parts = rest.split(":", 3);
                        if (parts.length == 3) {
                            boolean autoConfirm = "1".equals(parts[0]);
                            int confirmKey = Integer.parseInt(parts[1]);
                            String value = parts[2];

                            boolean ok = smartInjectTextToActiveField(value, -1);
                            if (!ok) {
                                for (int i = 0; i < value.length(); i++) {
                                    tapKeyHelper((int) value.charAt(i), 25, 20);
                                }
                            }
                            dirty = true;
                            if (autoConfirm) {
                                Thread.sleep(120);
                                tapKeyHelper(confirmKey, 40, 50);
                            }
                        }
                    } else if (line.startsWith("ADD_POINTS:")) {
                        String[] parts = line.substring(11).split(":");
                        if (parts.length >= 4) {
                            String mode = parts[0];
                            int slotOffset = Integer.parseInt(parts[1]);
                            int amount = Math.max(1, Math.min(Integer.parseInt(parts[2]), 5000));
                            int confirmKey = Integer.parseInt(parts[3]);

                            new Thread(() -> {
                                try {
                                    for (int i = 0; i < slotOffset; i++) {
                                        tapKeyHelper(-2, 35, 70);
                                    }
                                    if ("dialog".equalsIgnoreCase(mode)) {
                                        tapKeyHelper(-5, 45, 220);
                                        String amtStr = String.valueOf(amount);
                                        boolean injected = smartInjectTextToActiveField(amtStr, -1);
                                        if (!injected) {
                                            for (int i = 0; i < amtStr.length(); i++) {
                                                tapKeyHelper((int) amtStr.charAt(i), 25, 25);
                                            }
                                        }
                                        Thread.sleep(140);
                                        tapKeyHelper(confirmKey, 45, 80);
                                        System.err.println("[VM-SKILL] \u0110\u00e3 truy\u1ec1n s\u1ed1 l\u01b0\u1ee3ng c\u1ed9ng (" + amount + ") qua h\u1ed9p tho\u1ea1i!");
                                    } else {
                                        int loops = Math.min(amount, 200);
                                        System.err.println("[VM-SKILL] \u0110ang t\u1ef1 \u0111\u1ed9ng c\u1ed9ng l\u1eb7p " + loops + " l\u1ea7n...");
                                        for (int i = 0; i < loops && running; i++) {
                                            tapKeyHelper(-5, 30, 55);
                                            if (confirmKey != -5) {
                                                tapKeyHelper(confirmKey, 30, 55);
                                            }
                                        }
                                        System.err.println("[VM-SKILL] Ho\u00e0n t\u1ea5t c\u1ed9ng " + loops + " \u0111i\u1ec3m/skill!");
                                    }
                                } catch (Throwable ignored) {}
                            }, "IPC-AutoAddPoints").start();
                        }
                    } else if (line.startsWith("AUTO_SELL:") || line.startsWith("SELL_SETUP:")) {
                        // Dinh dang 1 (Menu Setup theo vi tri):
                        // AUTO_SELL:<1|0|now|diag>:<white>:<green>:<yellow>:<purple>:<orange>:<locked>:<unlocked>:<enhanced>[:<bagThreshold>]
                        // Dinh dang 2 (Key-Value linh hoat):
                        // SELL_SETUP:enabled=1,white=1,green=1,yellow=1,purple=0,orange=0,locked=1,unlocked=1,enhanced=0,threshold=40
                        String payload = line.startsWith("AUTO_SELL:")
                                ? line.substring(10).trim()
                                : line.substring(11).trim();

                        if ("diag".equalsIgnoreCase(payload)) {
                            dumpInventoryAutoSellDiagnostics();
                            continue;
                        }
                        if ("now".equalsIgnoreCase(payload)) {
                            autoSellNowRequested = true;
                            startAutoQuestLoop();
                            System.err.println("[AUTO-SELL] Y\u00eau c\u1ea7u B\u00c1N NGAY v\u1eadt ph\u1ea9m t\u1eeb Menu Setup...");
                            continue;
                        }

                        if (payload.contains("=")) {
                            String[] kvPairs = payload.split("[,:]");
                            for (String kv : kvPairs) {
                                String[] p = kv.split("=", 2);
                                if (p.length != 2) continue;
                                String k = p[0].trim().toLowerCase();
                                String v = p[1].trim();
                                boolean bVal = "1".equals(v) || "true".equalsIgnoreCase(v);
                                switch (k) {
                                    case "enabled": autoSellEnabled = bVal; break;
                                    case "white": case "trang": autoSellColorFlags[0] = bVal; break;
                                    case "green": case "blue": case "xanh": autoSellColorFlags[1] = bVal; break;
                                    case "yellow": case "vang": autoSellColorFlags[2] = bVal; break;
                                    case "purple": case "tim": autoSellColorFlags[3] = bVal; break;
                                    case "orange": case "cam": autoSellColorFlags[4] = bVal; break;
                                    case "locked": case "khoa": autoSellLocked = bVal; break;
                                    case "unlocked": case "khongkhoa": autoSellUnlocked = bVal; break;
                                    case "enhanced": case "cuonghoa": autoSellEnhanced = bVal; break;
                                    case "unenhanced": autoSellUnenhanced = bVal; break;
                                    case "threshold": case "bag":
                                        try { autoSellBagThreshold = Math.max(1, Integer.parseInt(v)); } catch (Throwable ignored) {}
                                        break;
                                }
                            }
                        } else {
                            String[] parts = payload.split(":");
                            if (parts.length >= 1) {
                                if ("now".equalsIgnoreCase(parts[0])) {
                                    autoSellNowRequested = true;
                                } else {
                                    autoSellEnabled = "1".equals(parts[0]) || "true".equalsIgnoreCase(parts[0]);
                                }
                            }
                            if (parts.length >= 6) {
                                autoSellColorFlags[0] = "1".equals(parts[1]); // Trang
                                autoSellColorFlags[1] = "1".equals(parts[2]); // Xanh
                                autoSellColorFlags[2] = "1".equals(parts[3]); // Vang
                                autoSellColorFlags[3] = "1".equals(parts[4]); // Tim
                                autoSellColorFlags[4] = "1".equals(parts[5]); // Cam
                            }
                            if (parts.length >= 8) {
                                autoSellLocked = "1".equals(parts[6]);   // Do khoa
                                autoSellUnlocked = "1".equals(parts[7]); // Do khong khoa
                            }
                            if (parts.length >= 9) {
                                autoSellEnhanced = "1".equals(parts[8]); // Do da cuong hoa (+1 tro len)
                            }
                            if (parts.length >= 10) {
                                try {
                                    autoSellBagThreshold = Math.max(1, Integer.parseInt(parts[9]));
                                } catch (Throwable ignored) {}
                            }
                            if (autoSellNowRequested) {
                                startAutoQuestLoop();
                            }
                        }

                        System.err.println(String.format(
                                "[AUTO-SELL] C\u1eadp nh\u1eadt Menu Setup B\u00e1n \u0110\u1ed3: B\u1eadt=%b | M\u00e0u=[Tr\u1eafng:%b, Xanh:%b, V\u00e0ng:%b, T\u00edm:%b, Cam:%b] "
                                        + "| Kh\u00f3a=%b | Kh\u00f4ngKh\u00f3a=%b | \u0110\u00e3C\u01b0\u1eddngH\u00f3a=%b | Ng\u01b0\u1ee1ng=%d \u00f4",
                                autoSellEnabled, autoSellColorFlags[0], autoSellColorFlags[1],
                                autoSellColorFlags[2], autoSellColorFlags[3], autoSellColorFlags[4],
                                autoSellLocked, autoSellUnlocked, autoSellEnhanced, autoSellBagThreshold));
                        dirty = true;
                    } else if (line.startsWith("AUTO_FARM:")) {
                        // Lenh bat/tat nhanh che do chi Tu Dong Danh Quai Theo Lv (+-2 Lv, uu tien -2 Lv)
                        String mode = line.substring(10).trim();
                        autoQuestFilter = "farm";
                        autoQuestEnabled = "1".equals(mode) || "true".equalsIgnoreCase(mode);
                        if (autoQuestEnabled) {
                            startAutoQuestLoop();
                        } else {
                            releaseAllDirectionalKeys();
                        }
                        dirty = true;
                    } else if (line.startsWith("AUTO_ZONE:")) {
                        // Định dạng hỗ trợ:
                        // 1. AUTO_ZONE:1 hoặc AUTO_ZONE:0 (Bật/tắt tự động né người đổi khu)
                        // 2. AUTO_ZONE:1:2 (Bật tự động đổi khu khi có >= 2 người tranh bãi)
                        // 3. AUTO_ZONE:goto:3 (Ép chuyển ngay sang Khu 3 - index = 2)
                        // 4. AUTO_ZONE:diag (In bảng chẩn đoán danh sách khu và số người ra stderr)
                        String payload = line.substring(10).trim();
                        if ("diag".equalsIgnoreCase(payload)) {
                            dumpAutoZoneDiagnostics();
                            continue;
                        }
                        String[] parts = payload.split(":");
                        if (parts.length >= 2 && "goto".equalsIgnoreCase(parts[0])) {
                            try {
                                int humanZone = Integer.parseInt(parts[1].trim());
                                manualTargetZoneRequest = Math.max(0, humanZone - 1);
                                startAutoQuestLoop();
                                System.err.println("[AUTO-ZONE] Y\u00eau c\u1ea7u chuy\u1ec3n ngay sang Khu " + humanZone);
                            } catch (Throwable ignored) {}
                            continue;
                        }
                        if (parts.length >= 1) {
                            autoZoneEnabled = "1".equals(parts[0]) || "true".equalsIgnoreCase(parts[0]);
                        }
                        if (parts.length >= 2) {
                            try {
                                autoZoneMaxPlayersInSpot = Math.max(1, Integer.parseInt(parts[1].trim()));
                            } catch (Throwable ignored) {}
                        }
                        System.err.println("[AUTO-ZONE] C\u1eadp nh\u1eadt c\u1ea5u h\u00ecnh: B\u1eadt=" + autoZoneEnabled
                                + " | S\u1ed1 ng\u01b0\u1eddi tranh b\u00e3i t\u1ed1i \u0111a=" + autoZoneMaxPlayersInSpot);
                        dirty = true;
                    }   
                    else if (line.startsWith("AUTO_POTENTIAL:") || line.startsWith("AUTO_POINT:")) {
                        // Dinh dang: AUTO_POTENTIAL:<1|0|now>:<str>:<dex>:<vit>:<int>
                        String payload = line.startsWith("AUTO_POTENTIAL:")
                                ? line.substring(15)
                                : line.substring(11);
                        String[] parts = payload.split(":");
                        if (parts.length >= 5) {
                            String mode = parts[0];
                            autoPotentialPerLevel[0] = Math.max(0, Math.min(5, Integer.parseInt(parts[1])));
                            autoPotentialPerLevel[1] = Math.max(0, Math.min(5, Integer.parseInt(parts[2])));
                            autoPotentialPerLevel[2] = Math.max(0, Math.min(5, Integer.parseInt(parts[3])));
                            autoPotentialPerLevel[3] = Math.max(0, Math.min(5, Integer.parseInt(parts[4])));

                            String cfgText = String.format("STR=%d, DEX=%d, VIT=%d, INT=%d",
                                    autoPotentialPerLevel[0], autoPotentialPerLevel[1],
                                    autoPotentialPerLevel[2], autoPotentialPerLevel[3]);

                            if ("now".equalsIgnoreCase(mode)) {
                                new Thread(() -> {
                                    ClassLoader cl = getGameClassLoader();
                                    if (cl != null) {
                                        applyAutoPotentialPoints(getRef(cl), 1, "C\u1ed9ng th\u1ee7 c\u00f4ng t\u1eeb Menu");
                                    }
                                }, "IPC-AutoPotNow").start();
                            } else {
                                autoPotentialEnabled = "1".equals(mode);
                                System.err.println("[AUTO-POTENTIAL] "
                                        + (autoPotentialEnabled ? "\u0110\u00c3 B\u1eacT" : "\u0110\u00c3 T\u1eaeT")
                                        + " t\u1ef1 \u0111\u1ed9ng c\u1ed9ng ti\u1ec1m n\u0103ng m\u1ed7i khi l\u00ean Lv (" + cfgText + "/Lv)");
                            }
                        }
                    } else if (line.startsWith("CHAT_CMD:")) {
                        String cmdText = line.substring(9);
                        new Thread(() -> {
                            try {
                                boolean injected = smartInjectTextToActiveField(cmdText, -1);
                                if (!injected) {
                                    tapKeyHelper(-5, 40, 180);
                                    injected = smartInjectTextToActiveField(cmdText, -1);
                                }
                                if (injected) {
                                    Thread.sleep(120);
                                    tapKeyHelper(-5, 40, 50);
                                    System.err.println("[VM-CMD] \u0110\u00e3 g\u1eedi l\u1ec7nh k\u00e8m tham s\u1ed1: " + cmdText);
                                }
                            } catch (Throwable ignored) {}
                        }, "IPC-ChatCmd").start();
                    } else if (line.startsWith("AUTO_QUEST:")) {
                        String[] parts = line.substring(11).split(":");
                        String mode = parts.length > 0 ? parts[0] : "0";

                        // Lenh chan doan AUTO_QUEST:diag
                        if ("diag".equalsIgnoreCase(mode)) {
                            dumpAutoQuestDiagnostics();
                            continue;
                        }
                        // [KICH-BAN] AUTO_QUEST:script:<1|0|load|list|status|adopt|reset> | AUTO_QUEST:record:<1|0|save|clear>
                        //            AUTO_QUEST:goto:<questId> | AUTO_QUEST:goto:#N
                        if ("script".equalsIgnoreCase(mode)) {
                            handleScriptCommand(parts);
                            continue;
                        }
                        if ("record".equalsIgnoreCase(mode)) {
                            handleRecordCommand(parts);
                            continue;
                        }
                        if ("goto".equalsIgnoreCase(mode)) {
                            scriptGoto(parts.length > 1 ? parts[1] : "");
                            continue;
                        }
                        // Lenh bat/tat dong trang thai IPC AQ_STATUS:{json}
                        if ("status".equalsIgnoreCase(mode)) {
                            aqStatusIpcEnabled = parts.length > 1 && "1".equals(parts[1]);
                            System.err.println("[AUTO-QUEST] B\u1eadt xu\u1ea5t d\u00f2ng IPC AQ_STATUS: " + aqStatusIpcEnabled);
                            continue;
                        }

                        if (parts.length > 1 && !parts[1].isEmpty()) {
                            autoQuestFilter = parts[1];
                            if ("script".equalsIgnoreCase(autoQuestFilter)) {
                                loadQuestScript(true);
                                questScriptMode = true;
                            } else {
                                questScriptMode = false;
                                scriptClearOverride();
                            }
                        }
                        if (parts.length > 2 && !parts[2].isEmpty()) {
                            autoPotionEnabled = "1".equals(parts[2]);
                        }
                        if (parts.length > 3 && !parts[3].isEmpty()) {
                            aqStatusIpcEnabled = "1".equals(parts[3]);
                        }
                        if (parts.length > 4 && !parts[4].isEmpty()) {
                            autoBuyPotionEnabled = "1".equals(parts[4]);
                        }

                        if ("step".equalsIgnoreCase(mode)) {
                            System.err.println("[AUTO-QUEST] Y\u00eau c\u1ea7u th\u1ef1c thi \u0111\u01a1n lu\u1ed3ng 1 b\u01b0\u1edbc nhi\u1ec7m v\u1ee5 (" + autoQuestFilter + ")...");
                            stepRequested = true;
                            startAutoQuestLoop();
                        } else {
                            autoQuestEnabled = "1".equals(mode);
                            if (autoQuestEnabled) {
                                deferredQuestUntilMs.clear();
                                questAcceptFailCounts.clear();
                                maxLevelCompletedSteadyMode = false;
                                startAutoQuestLoop();
                            } else {
                                releaseAllDirectionalKeys();
                                System.err.println("[AUTO-QUEST] \u0110\u00e3 t\u1ea1m d\u1eebng T\u1ef1 \u0110\u1ed9ng L\u00e0m Nhi\u1ec7m V\u1ee5.");
                            }
                        }
                        dirty = true;
                    } else if (line.startsWith("SET_USER:")) {
                        String text = line.substring(9);
                        try {
                            ClassLoader cl = getGameClassLoader();
                            if (cl != null) {
                                Class<?> loginClass = Class.forName("n0001.n0068", false, cl);
                                Field fUser = loginClass.getDeclaredField("n0043");
                                fUser.setAccessible(true);
                                Object uTField = fUser.get(null);
                                if (uTField != null) {
                                    setTFieldValue(uTField, text);
                                    dirty = true;
                                }
                            }
                        } catch (Throwable ignored) {}
                    } else if (line.startsWith("SET_PASS:")) {
                        String text = line.substring(9);
                        try {
                            ClassLoader cl = getGameClassLoader();
                            if (cl != null) {
                                Class<?> loginClass = Class.forName("n0001.n0068", false, cl);
                                Field fPass = loginClass.getDeclaredField("n0044");
                                fPass.setAccessible(true);
                                Object pTField = fPass.get(null);
                                if (pTField != null) {
                                    setTFieldValue(pTField, text);
                                    dirty = true;
                                }
                            }
                        } catch (Throwable ignored) {}
                    } else if (line.equals("DO_LOGIN")) {
                        try {
                            ClassLoader cl = getGameClassLoader();
                            if (cl != null) {
                                Class<?> loginClass = Class.forName("n0001.n0068", false, cl);
                                Field fUser = loginClass.getDeclaredField("n0043");
                                Field fPass = loginClass.getDeclaredField("n0044");
                                fUser.setAccessible(true);
                                fPass.setAccessible(true);
                                Object uTField = fUser.get(null);
                                Object pTField = fPass.get(null);

                                String u = "";
                                String p = "";
                                if (uTField != null) {
                                    Method mGet = uTField.getClass().getDeclaredMethod("n0010");
                                    mGet.setAccessible(true);
                                    u = (String) invokeMethod(mGet, uTField);
                                }
                                if (pTField != null) {
                                    Method mGet = pTField.getClass().getDeclaredMethod("n0010");
                                    mGet.setAccessible(true);
                                    p = (String) invokeMethod(mGet, pTField);
                                }

                                Method mLogin = loginClass.getDeclaredMethod("n0001", String.class, String.class);
                                mLogin.setAccessible(true);
                                invokeMethod(mLogin, null, u, p);
                                dirty = true;
                            }
                        } catch (Throwable ignored) {}
                    } else if (line.startsWith("MD:")) {
                        String[] parts = line.substring(3).split(",");
                        platform.pointerPressed(Integer.parseInt(parts[0]), Integer.parseInt(parts[1]));
                        dirty = true;
                    } else if (line.startsWith("MU:")) {
                        String[] parts = line.substring(3).split(",");
                        platform.pointerReleased(Integer.parseInt(parts[0]), Integer.parseInt(parts[1]));
                        dirty = true;
                    } else if (line.startsWith("MM:")) {
                        String[] parts = line.substring(3).split(",");
                        platform.pointerDragged(
                                Integer.parseInt(parts.length > 0 ? parts[0] : "0"),
                                Integer.parseInt(parts.length > 1 ? parts[1] : "0"));
                        dirty = true;
                    } else if (line.startsWith("RENDER:")) {
                        String val = line.substring(7).trim();
                        boolean enable = "1".equals(val) || "true".equalsIgnoreCase(val);
                        renderEnabled = enable;
                        if (enable) {
                            lastPixels = null;
                            dirty = true;
                            sendCurrentFrame(true); // Ép gửi ngay 1 frame khi bật lại hình
                        } else {
                            lastPixels = null; // Giải phóng mảng pixel trong RAM Java khi tắt hình
                        }
                    } else if (line.equals("SYNC")) {
                        lastPixels = null;
                        dirty = true;
                        if (renderEnabled) {
                            sendCurrentFrame(true); // Ép gửi ngay frame hiện tại khi mở web / F5
                        }
                    } else if (line.equals("STOP")) {
                        running = false;
                        releaseAllDirectionalKeys();
                        saveSharedWorldKnowledgeIfNeeded(true);
                        exitVM(0);
                    }
                } catch (Exception ignored) {}
            }
        } catch (Exception ignored) {}
    }

    public static boolean isAutoQuestActive() {
        return autoQuestEnabled;
    }

    public static String getCurrentQuestFilter() {
        return autoQuestFilter;
    }

    public static boolean isAutoPotionActive() {
        return autoPotionEnabled;
    }

    public static boolean isAutoBuyPotionActive() {
        return autoBuyPotionEnabled;
    }

    public static boolean isAutoSellActive() {
        return autoSellEnabled;
    }

    public static String getInGameAutoQuestSummary() {
        String filterName;
        if ("main".equalsIgnoreCase(autoQuestFilter)) {
            filterName = "Chỉ NV Chính Tuyến";
        } else if ("sub".equalsIgnoreCase(autoQuestFilter)) {
            filterName = "Chỉ NV Phụ / Hằng Ngày";
        } else if ("script".equalsIgnoreCase(autoQuestFilter)) {
            filterName = "Kịch Bản Chuẩn Lv.1 -> 135";
        } else if ("farm".equalsIgnoreCase(autoQuestFilter)) {
            filterName = "Chỉ Cày Quái Theo Level";
        } else {
            filterName = "Tất cả NV (Chính + Phụ)";
        }
        int totalSteps = (questScript != null) ? questScript.size() : 38;
        int curStep = Math.min(totalSteps, Math.max(1, scriptIdx + 1));
        return "Trạng thái Auto NV: " + (autoQuestEnabled ? "ĐANG BẬT" : "ĐANG TẮT")
                + "\nLoại NV: " + filterName
                + "\nTiến trình: " + currentAqStateLabel + " - " + currentAqDetailLabel
                + "\nKịch bản Lv.1->135: Bước " + curStep + "/" + totalSteps
                + "\nTự dùng HP/MP: " + (autoPotionEnabled ? "BẬT" : "TẮT")
                + " | Tự mua HP/MP: " + (autoBuyPotionEnabled ? "BẬT" : "TẮT")
                + "\nTự bán đồ đầy túi: " + (autoSellEnabled ? "BẬT" : "TẮT") + " (Đã bán: " + totalAutoSoldItemsCount + ")";
    }

    public static BufferedImage getCurrentLcdFrame() {
        return platform != null ? platform.getLCD() : null;
    }

    public static MobilePlatform getPlatform() {
        return platform;
    }

    public static void setRenderStreamingEnabled(boolean enabled) {
        renderEnabled = enabled;
    }

    public static void openInGameVipModMenu() {
        try {
            ClassLoader cl = getGameClassLoader();
            if (cl != null) {
                Class<?> cls = Class.forName("n0001.n0235", false, cl);
                Method m = cls.getDeclaredMethod("n0009");
                m.setAccessible(true);
                m.invoke(null);
                dirty = true;
            }
        } catch (Throwable ignored) {}
    }

    public static void openInGameAutoQuestMenu() {
        try {
            ClassLoader cl = getGameClassLoader();
            if (cl != null) {
                Class<?> cls = Class.forName("n0001.n0241", false, cl);
                Method m = cls.getDeclaredMethod("openAutoQuestMainMenu");
                m.setAccessible(true);
                m.invoke(null);
                dirty = true;
            }
        } catch (Throwable ignored) {}
    }

    public static void openInGameQuestTypeMenu() {
        try {
            ClassLoader cl = getGameClassLoader();
            if (cl != null) {
                Class<?> cls = Class.forName("n0001.n0241", false, cl);
                Method m = cls.getDeclaredMethod("openQuestTypeSelectorMenu");
                m.setAccessible(true);
                m.invoke(null);
                dirty = true;
            }
        } catch (Throwable ignored) {}
    }

    public static void ensurePcTFieldReady() {
        try {
            ClassLoader cl = getGameClassLoader();
            if (cl == null) return;
            RefCache ref = getRef(cl);
            Class<?> tfCls = (ref != null && ref.clsTField != null)
                    ? ref.clsTField : Class.forName("n0001.n0155", false, cl);

            // 1. Luôn bật chế độ bàn phím QWERTY (n0048 = true) để nhập trực tiếp chữ & số từ PC
            Field fQty = tfCls.getDeclaredField("n0048");
            fQty.setAccessible(true);
            if (!fQty.getBoolean(null)) {
                fQty.setBoolean(null, true);
            }

            // 2. Luôn khóa cờ n0051 = true để TField.n0007() KHÔNG mở javax.microedition.lcdui.TextBox ẩn
            // (tránh lỗi Display.setCurrent(textBox) làm đơ cảm ứng chuột và không bấm xuống ô Mật khẩu được!)
            Field fTextBoxOpen = tfCls.getDeclaredField("n0051");
            fTextBoxOpen.setAccessible(true);
            if (!fTextBoxOpen.getBoolean(null)) {
                fTextBoxOpen.setBoolean(null, true);
            }

            // 3. Nếu Display.getCurrent() bị chuyển sang TextBox, khôi phục ngay về MotherCanvas
            try {
                Class<?> midletCls = Class.forName("com.silverknight.TemMidlet", false, cl);
                Field fCanvas = midletCls.getDeclaredField("a");
                fCanvas.setAccessible(true);
                Object motherCanvas = fCanvas.get(null);
                if (motherCanvas instanceof javax.microedition.lcdui.Displayable) {
                    javax.microedition.lcdui.Display disp = Mobile.getDisplay();
                    if (disp != null && disp.getCurrent() != motherCanvas) {
                        disp.setCurrent((javax.microedition.lcdui.Displayable) motherCanvas);
                    }
                }
            } catch (Throwable ignored) {}

            // 4. Bật cờ tự hủy focus khi click ra ngoài (n0046 = true) cho cả ô Tài khoản (n0043) và Mật khẩu (n0044)
            Class<?> loginCls = (ref != null && ref.clsLogin != null)
                    ? ref.clsLogin : Class.forName("n0001.n0068", false, cl);
            Field fAutoUnfocus = tfCls.getDeclaredField("n0046");
            fAutoUnfocus.setAccessible(true);

            Field fUser = loginCls.getDeclaredField("n0043");
            Field fPass = loginCls.getDeclaredField("n0044");
            fUser.setAccessible(true);
            fPass.setAccessible(true);
            Object uTf = fUser.get(null);
            Object pTf = fPass.get(null);
            if (uTf != null && !fAutoUnfocus.getBoolean(uTf)) {
                fAutoUnfocus.setBoolean(uTf, true);
            }
            if (pTf != null && !fAutoUnfocus.getBoolean(pTf)) {
                fAutoUnfocus.setBoolean(pTf, true);
            }
        } catch (Throwable ignored) {}
    }

    public static boolean isOnLoginScreen() {
        try {
            ClassLoader cl = getGameClassLoader();
            if (cl == null) return false;
            Class<?> canvasCls = Class.forName("n0001.n0038", false, cl);
            Field fCurScr = canvasCls.getDeclaredField("n0001");
            Field fLoginScr = canvasCls.getDeclaredField("n0002");
            fCurScr.setAccessible(true);
            fLoginScr.setAccessible(true);
            Object cur = fCurScr.get(null);
            Object login = fLoginScr.get(null);
            return cur != null && cur == login;
        } catch (Throwable ignored) {
            return false;
        }
    }

    public static boolean isAnyTFieldFocused() {
        try {
            ClassLoader cl = getGameClassLoader();
            if (cl == null) return false;
            RefCache ref = getRef(cl);
            Class<?> tfCls = (ref != null && ref.clsTField != null)
                    ? ref.clsTField : Class.forName("n0001.n0155", false, cl);
            Field fFocus = tfCls.getDeclaredField("n0045");
            fFocus.setAccessible(true);

            if (isOnLoginScreen()) {
                Class<?> loginCls = Class.forName("n0001.n0068", false, cl);
                Field fUser = loginCls.getDeclaredField("n0043");
                Field fPass = loginCls.getDeclaredField("n0044");
                fUser.setAccessible(true);
                fPass.setAccessible(true);
                Object uTf = fUser.get(null);
                Object pTf = fPass.get(null);
                if (uTf != null && fFocus.getBoolean(uTf)) return true;
                if (pTf != null && fFocus.getBoolean(pTf)) return true;
            }

            List<Object> all = findAllActiveTFields(cl, tfCls);
            for (Object tf : all) {
                if (tf != null && fFocus.getBoolean(tf)) {
                    return true;
                }
            }
        } catch (Throwable ignored) {}
        return false;
    }

    public static void focusLoginField(boolean passwordField) {
        try {
            ensurePcTFieldReady();
            ClassLoader cl = getGameClassLoader();
            if (cl == null) return;
            Class<?> canvasCls = Class.forName("n0001.n0038", false, cl);
            Field fLoginScr = canvasCls.getDeclaredField("n0002");
            fLoginScr.setAccessible(true);
            Object loginScr = fLoginScr.get(null);

            Class<?> loginCls = Class.forName("n0001.n0068", false, cl);
            if (loginScr != null) {
                Field fSubState = loginCls.getDeclaredField("n0041");
                fSubState.setAccessible(true);
                if (fSubState.getInt(loginScr) == 0) {
                    fSubState.setInt(loginScr, 1);
                }
            }

            Field fUser = loginCls.getDeclaredField("n0043");
            Field fPass = loginCls.getDeclaredField("n0044");
            fUser.setAccessible(true);
            fPass.setAccessible(true);
            Object uTf = fUser.get(null);
            Object pTf = fPass.get(null);

            Class<?> tfCls = Class.forName("n0001.n0155", false, cl);
            Method mSetFocus = tfCls.getDeclaredMethod("n0001", boolean.class);
            mSetFocus.setAccessible(true);

            if (uTf != null) invokeMethod(mSetFocus, uTf, !passwordField);
            if (pTf != null) invokeMethod(mSetFocus, pTf, passwordField);
            dirty = true;
        } catch (Throwable ignored) {}
    }

    public static void loginWithCredentials(String username, String password, boolean submitNow) {
        try {
            ensurePcTFieldReady();
            ClassLoader cl = getGameClassLoader();
            if (cl == null) return;
            Class<?> canvasCls = Class.forName("n0001.n0038", false, cl);
            Field fLoginScr = canvasCls.getDeclaredField("n0002");
            fLoginScr.setAccessible(true);
            Object loginScr = fLoginScr.get(null);

            Class<?> loginCls = Class.forName("n0001.n0068", false, cl);
            if (loginScr != null) {
                Field fSubState = loginCls.getDeclaredField("n0041");
                fSubState.setAccessible(true);
                if (fSubState.getInt(loginScr) == 0) {
                    fSubState.setInt(loginScr, 1);
                }
            }

            Field fUser = loginCls.getDeclaredField("n0043");
            Field fPass = loginCls.getDeclaredField("n0044");
            fUser.setAccessible(true);
            fPass.setAccessible(true);
            Object uTf = fUser.get(null);
            Object pTf = fPass.get(null);

            if (uTf != null && username != null) {
                setTFieldValue(uTf, username);
            }
            if (pTf != null && password != null) {
                setTFieldValue(pTf, password);
            }
            focusLoginField(password == null || password.isEmpty());
            dirty = true;

            if (submitNow && username != null && password != null) {
                Method mLogin = loginCls.getDeclaredMethod("n0001", String.class, String.class);
                mLogin.setAccessible(true);
                invokeMethod(mLogin, null, username, password);
                dirty = true;
            }
        } catch (Throwable ignored) {}
    }

    private static void resolveLoginPointerClick(int gx, int gy) {
        try {
            ensurePcTFieldReady();
            if (!isOnLoginScreen()) return;
            ClassLoader cl = getGameClassLoader();
            if (cl == null) return;

            Class<?> canvasCls = Class.forName("n0001.n0038", false, cl);
            Field fLoginScr = canvasCls.getDeclaredField("n0002");
            fLoginScr.setAccessible(true);
            Object loginScr = fLoginScr.get(null);
            if (loginScr == null) return;

            Class<?> loginCls = Class.forName("n0001.n0068", false, cl);
            Field fSubState = loginCls.getDeclaredField("n0041");
            fSubState.setAccessible(true);
            if (fSubState.getInt(loginScr) != 1) return;

            Field fUser = loginCls.getDeclaredField("n0043");
            Field fPass = loginCls.getDeclaredField("n0044");
            fUser.setAccessible(true);
            fPass.setAccessible(true);
            Object uTf = fUser.get(null);
            Object pTf = fPass.get(null);
            if (uTf == null || pTf == null) return;

            Class<?> tfCls = Class.forName("n0001.n0155", false, cl);
            Field fX = tfCls.getDeclaredField("n0041");
            Field fY = tfCls.getDeclaredField("n0042");
            Field fW = tfCls.getDeclaredField("n0043");
            Field fH = tfCls.getDeclaredField("n0044");
            fX.setAccessible(true);
            fY.setAccessible(true);
            fW.setAccessible(true);
            fH.setAccessible(true);

            int ux = fX.getInt(uTf), uy = fY.getInt(uTf), uw = fW.getInt(uTf), uh = fH.getInt(uTf);
            int px = fX.getInt(pTf), py = fY.getInt(pTf), pw = fW.getInt(pTf), ph = fH.getInt(pTf);

            boolean hitPass = (gx >= px - 8 && gx <= px + pw + 8 && gy >= py - 6 && gy <= py + ph + 8);
            boolean hitUser = (gx >= ux - 8 && gx <= ux + uw + 8 && gy >= uy - 6 && gy <= uy + uh + 8);

            if (hitPass) {
                focusLoginField(true);
            } else if (hitUser) {
                focusLoginField(false);
            }
        } catch (Throwable ignored) {}
    }

    private static boolean handleLoginKeyNavigation(int key) {
        try {
            ensurePcTFieldReady();
            if (!isOnLoginScreen()) return false;
            ClassLoader cl = getGameClassLoader();
            if (cl == null) return false;

            Class<?> canvasCls = Class.forName("n0001.n0038", false, cl);
            Field fLoginScr = canvasCls.getDeclaredField("n0002");
            fLoginScr.setAccessible(true);
            Object loginScr = fLoginScr.get(null);
            if (loginScr == null) return false;

            Class<?> loginCls = Class.forName("n0001.n0068", false, cl);
            Field fSubState = loginCls.getDeclaredField("n0041");
            fSubState.setAccessible(true);
            if (fSubState.getInt(loginScr) != 1) return false;

            if (key == -1 || key == -2 || key == -99) {
                Field fUser = loginCls.getDeclaredField("n0043");
                fUser.setAccessible(true);
                Object uTf = fUser.get(null);
                Class<?> tfCls = Class.forName("n0001.n0155", false, cl);
                Field fFocus = tfCls.getDeclaredField("n0045");
                fFocus.setAccessible(true);
                boolean userFocused = (uTf != null && fFocus.getBoolean(uTf));
                focusLoginField(userFocused);
                dirty = true;
                return true;
            }
        } catch (Throwable ignored) {}
        return false;
    }

    public static void dispatchCommand(String rawLine) {
        if (rawLine == null) return;
        String line = rawLine.trim();
        if (line.isEmpty()) return;
        try {
            ensurePcTFieldReady();
            if (line.equals("SWITCH_LOGIN_FIELD")) {
                handleLoginKeyNavigation(-99);
                dirty = true;
            } else if (line.startsWith("KD:") && platform != null) {
                int key = Integer.parseInt(line.substring(3));
                if (!handleLoginKeyNavigation(key)) {
                    platform.keyPressed(key);
                }
                dirty = true;
            } else if (line.startsWith("KU:") && platform != null) {
                int key = Integer.parseInt(line.substring(3));
                platform.keyReleased(key);
                dirty = true;
            } else if (line.startsWith("KP:")) {
                int key = Integer.parseInt(line.substring(3));
                if (!handleLoginKeyNavigation(key)) {
                    tapKeyHelper(key, 30, 0);
                }
                dirty = true;
            } else if (line.startsWith("MD:") && platform != null) {
                String[] parts = line.substring(3).split(",");
                int gx = Integer.parseInt(parts[0]);
                int gy = Integer.parseInt(parts[1]);
                platform.pointerPressed(gx, gy);
                resolveLoginPointerClick(gx, gy);
                dirty = true;
            } else if (line.startsWith("MU:") && platform != null) {
                String[] parts = line.substring(3).split(",");
                int gx = Integer.parseInt(parts[0]);
                int gy = Integer.parseInt(parts[1]);
                platform.pointerReleased(gx, gy);
                resolveLoginPointerClick(gx, gy);
                dirty = true;
            } else if (line.startsWith("MM:") && platform != null) {
                String[] parts = line.substring(3).split(",");
                platform.pointerDragged(
                        Integer.parseInt(parts.length > 0 ? parts[0] : "0"),
                        Integer.parseInt(parts.length > 1 ? parts[1] : "0"));
                dirty = true;
            } else if (line.startsWith("TEXT:")) {
                String text = line.substring(5);
                boolean directSet = smartInjectTextToActiveField(text, -1);
                if (!directSet) {
                    for (int i = 0; i < text.length(); i++) {
                        tapKeyHelper((int) text.charAt(i), 20, 15);
                    }
                }
                dirty = true;
            } else if (line.startsWith("AUTO_SELL:")) {
                String[] parts = line.substring(10).trim().split(":");
                if (parts.length >= 1) {
                    if ("now".equalsIgnoreCase(parts[0])) {
                        autoSellNowRequested = true;
                        startAutoQuestLoop();
                    } else if ("diag".equalsIgnoreCase(parts[0])) {
                        dumpInventoryAutoSellDiagnostics();
                    } else {
                        autoSellEnabled = "1".equals(parts[0]) || "true".equalsIgnoreCase(parts[0]);
                    }
                }
                dirty = true;
            } else if (line.startsWith("AUTO_QUEST:")) {
                String[] parts = line.substring(11).split(":");
                String mode = parts.length > 0 ? parts[0] : "0";
                if ("diag".equalsIgnoreCase(mode)) {
                    dumpAutoQuestDiagnostics();
                    return;
                }
                if ("script".equalsIgnoreCase(mode)) {
                    handleScriptCommand(parts);
                    return;
                }
                if (parts.length > 1 && !parts[1].isEmpty()) {
                    autoQuestFilter = parts[1];
                    if ("script".equalsIgnoreCase(autoQuestFilter)) {
                        loadQuestScript(true);
                        questScriptMode = true;
                    } else {
                        ensureQuestScriptLoaded();
                        questScriptMode = false;
                    }
                }
                if (parts.length > 2 && !parts[2].isEmpty()) {
                    autoPotionEnabled = "1".equals(parts[2]);
                }
                if (parts.length > 3 && !parts[3].isEmpty()) {
                    aqStatusIpcEnabled = "1".equals(parts[3]);
                }
                if (parts.length > 4 && !parts[4].isEmpty()) {
                    autoBuyPotionEnabled = "1".equals(parts[4]);
                }
                if ("step".equalsIgnoreCase(mode)) {
                    stepRequested = true;
                    startAutoQuestLoop();
                } else {
                    autoQuestEnabled = "1".equals(mode);
                    if (autoQuestEnabled) {
                        deferredQuestUntilMs.clear();
                        questAcceptFailCounts.clear();
                        maxLevelCompletedSteadyMode = false;
                        startAutoQuestLoop();
                    } else {
                        releaseAllDirectionalKeys();
                    }
                }
                dirty = true;
            }
        } catch (Throwable ignored) {}
    }
}