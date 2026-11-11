const https = require('https');
const fs = require('fs');
const path = require('path');
const { execFileSync } = require('child_process');
const zlib = require('zlib');
const AdmZip = require('adm-zip');

const PROJECT_ROOT = path.join(__dirname, '..');
const TARGET_DIR = path.join(PROJECT_ROOT, 'public', 'lib', 'freej2me');
const TARGET_FILE = path.join(TARGET_DIR, 'freej2me.jar');
const META_FILE = path.join(TARGET_DIR, 'emulator-meta.json');
const TEMP_DIR = path.join(PROJECT_ROOT, 'data', '_temp_freej2me_build');
const PATCH_SRC_DIR = path.join(__dirname, 'freej2me-patches');

fs.mkdirSync(TARGET_DIR, { recursive: true });

// Đường dẫn tải mã nguồn ZIP chính thức từ GitHub (luôn hoạt động 100%)
const SOURCE_ZIP_URLS = [
  'https://codeload.github.com/hex007/freej2me/zip/refs/heads/master',
  'https://github.com/hex007/freej2me/archive/refs/heads/master.zip'
];

function downloadFile(url, destPath) {
  return new Promise((resolve, reject) => {
    https.get(
      url,
      { headers: { 'User-Agent': 'Mozilla/5.0 (Windows NT 10.0; Win64; x64)' } },
      (res) => {
        if (res.statusCode >= 300 && res.statusCode < 400 && res.headers.location) {
          return resolve(downloadFile(res.headers.location, destPath));
        }
        if (res.statusCode !== 200) {
          return reject(new Error(`HTTP ${res.statusCode}`));
        }
        const out = fs.createWriteStream(destPath);
        res.pipe(out);
        out.on('finish', () => {
          out.close(() => resolve(fs.statSync(destPath).size));
        });
      }
    ).on('error', reject);
  });
}

// Tự động tìm javac.exe và jar.exe trên Windows hoặc Linux/Termux
function findJdkTools() {
  const isWin = process.platform === 'win32';
  const javacName = isWin ? 'javac.exe' : 'javac';
  const jarName = isWin ? 'jar.exe' : 'jar';

  // 1. Kiểm tra trong PATH trước
  try {
    execFileSync(javacName, ['-version'], { stdio: 'ignore' });
    return { javacPath: javacName, jarPath: jarName };
  } catch (_) {}

  // 2. Kiểm tra trong JAVA_HOME
  if (process.env.JAVA_HOME) {
    const candidate = path.join(process.env.JAVA_HOME, 'bin', javacName);
    if (fs.existsSync(candidate)) {
      return {
        javacPath: candidate,
        jarPath: path.join(process.env.JAVA_HOME, 'bin', jarName)
      };
    }
  }

  // 3. Quét các thư mục cài đặt JDK phổ biến trên Windows
  if (isWin) {
    const searchRoots = [
      'C:\\Program Files\\Java',
      'C:\\Program Files\\Eclipse Adoptium',
      'C:\\Program Files\\Microsoft',
      'C:\\Program Files\\BellSoft',
      'C:\\Program Files\\Amazon Corretto',
      'C:\\Program Files\\OpenJDK',
      'C:\\Program Files (x86)\\Java'
    ];

    for (const root of searchRoots) {
      if (!fs.existsSync(root)) continue;
      const subDirs = fs.readdirSync(root);
      for (const sub of subDirs) {
        const candidate = path.join(root, sub, 'bin', 'javac.exe');
        if (fs.existsSync(candidate)) {
          return {
            javacPath: candidate,
            jarPath: path.join(root, sub, 'bin', 'jar.exe')
          };
        }
      }
    }
  }

  throw new Error(
    'Không tìm thấy JDK (javac). Hãy đảm bảo máy tính đã cài đặt Java JDK (8/11/17/21).'
  );
}

// Đệ quy lấy danh sách tất cả các file trong thư mục
function getAllFiles(dir, fileList = []) {
  if (!fs.existsSync(dir)) return fileList;
  for (const entry of fs.readdirSync(dir, { withFileTypes: true })) {
    const fullPath = path.join(dir, entry.name);
    if (entry.isDirectory()) {
      getAllFiles(fullPath, fileList);
    } else {
      fileList.push(fullPath);
    }
  }
  return fileList;
}

// Vá mã nguồn FreeJ2ME trước khi biên dịch để chạy mượt trên trình duyệt (CheerpJ 3.0)
function patchSourceFiles(srcRoot) {
  // 1. Vá Config.java: không bao giờ bật menu cấu hình chặn ngang (isRunning = false)
  const configJavaPath = path.join(srcRoot, 'org', 'recompile', 'freej2me', 'Config.java');
  if (fs.existsSync(configJavaPath)) {
    let content = fs.readFileSync(configJavaPath, 'utf8');
    content = content.replace(/isRunning\s*=\s*true\s*;/g, 'isRunning = false;');
    fs.writeFileSync(configJavaPath, content, 'utf8');
    console.log('🔧 Đã vá Config.java (vô hiệu hóa menu chờ isRunning).');
  }

  // 2. Vá FreeJ2ME.java: bỏ vòng lặp while(config.isRunning), tự chuyển đường dẫn file sang URL
  const mainJavaPath = path.join(srcRoot, 'org', 'recompile', 'freej2me', 'FreeJ2ME.java');
  if (fs.existsSync(mainJavaPath)) {
    let content = fs.readFileSync(mainJavaPath, 'utf8');
    content = content.replace(/while\s*\(\s*config\.isRunning\s*\)/g, 'while (false)');
    fs.writeFileSync(mainJavaPath, content, 'utf8');
    console.log('🔧 Đã vá FreeJ2ME.java (vào thẳng game ngay khi nạp xong).');
  }

  // 3. Vá MobilePlatform.java: chấp nhận cả đường dẫn "/str/game.jar" lẫn "file:///str/game.jar"
  const platformJavaPath = path.join(srcRoot, 'org', 'recompile', 'mobile', 'MobilePlatform.java');
  if (fs.existsSync(platformJavaPath)) {
    let content = fs.readFileSync(platformJavaPath, 'utf8');
    content = content.replace(
      'URL jar = new URL(jarurl);',
      'URL jar = (jarurl.startsWith("http:") || jarurl.startsWith("https:") || jarurl.startsWith("file:")) ? new URL(jarurl) : new java.io.File(jarurl).toURI().toURL();'
    );
    fs.writeFileSync(platformJavaPath, content, 'utf8');
    console.log('🔧 Đã vá MobilePlatform.java (hỗ trợ cả đường dẫn File và URL).');
  }

  // 4. Xóa Libretro.java và SDL.java (không cần thiết trên trình duyệt)
  ['Libretro.java', 'SDL.java'].forEach((file) => {
    const p = path.join(srcRoot, 'org', 'recompile', 'freej2me', file);
    if (fs.existsSync(p)) fs.unlinkSync(p);
  });

  // 5. Chép đè các file vá sẵn trong scripts/freej2me-patches (vd: javax.microedition.io.Connector đầy đủ).
  //    Bản Connector gốc của FreeJ2ME KHÔNG có hàm open(String) nên các game online (Teamobi/HSO...)
  //    sẽ crash ngay trong constructor (NoSuchMethodError) và chỉ hiện màn hình trắng.
  const copied = copyOverrides(PATCH_SRC_DIR, srcRoot);
  console.log(`🔧 Đã chép đè ${copied} file vá từ scripts/freej2me-patches (Connector HTTP/Socket).`);
}

// Chép đệ quy toàn bộ file từ thư mục vá vào cây mã nguồn (ghi đè file cùng đường dẫn)
function copyOverrides(fromDir, toDir) {
  if (!fs.existsSync(fromDir)) return 0;
  let count = 0;
  for (const entry of fs.readdirSync(fromDir, { withFileTypes: true })) {
    const from = path.join(fromDir, entry.name);
    const to = path.join(toDir, entry.name);
    if (entry.isDirectory()) {
      fs.mkdirSync(to, { recursive: true });
      count += copyOverrides(from, to);
    } else {
      fs.mkdirSync(path.dirname(to), { recursive: true });
      fs.copyFileSync(from, to);
      count++;
    }
  }
  return count;
}

// Hàm dự phòng đóng gói JAR chuẩn STORED (flags = 0) nếu không có sẵn jar.exe
function writeStoredJarFile(classesDir, outputJarPath) {
  const allFiles = getAllFiles(classesDir);
  const localParts = [];
  const centralParts = [];
  let offset = 0;

  for (const fullPath of allFiles) {
    const relPath = path.relative(classesDir, fullPath).replace(/\\/g, '/');
    const nameBuf = Buffer.from(relPath, 'utf8');
    const dataBuf = fs.readFileSync(fullPath);
    const crc = zlib.crc32(dataBuf) >>> 0;

    const localHeader = Buffer.alloc(30 + nameBuf.length);
    localHeader.writeUInt32LE(0x04034b50, 0);
    localHeader.writeUInt16LE(20, 4);
    localHeader.writeUInt16LE(0, 6); // flags = 0 (không dùng Data Descriptor)
    localHeader.writeUInt16LE(0, 8); // method = 0 (STORED)
    localHeader.writeUInt16LE(0, 10);
    localHeader.writeUInt16LE(0, 12);
    localHeader.writeUInt32LE(crc, 14);
    localHeader.writeUInt32LE(dataBuf.length, 18);
    localHeader.writeUInt32LE(dataBuf.length, 22);
    localHeader.writeUInt16LE(nameBuf.length, 26);
    localHeader.writeUInt16LE(0, 28);
    nameBuf.copy(localHeader, 30);

    const centralHeader = Buffer.alloc(46 + nameBuf.length);
    centralHeader.writeUInt32LE(0x02014b50, 0);
    centralHeader.writeUInt16LE(20, 4);
    centralHeader.writeUInt16LE(20, 6);
    centralHeader.writeUInt16LE(0, 8);
    centralHeader.writeUInt16LE(0, 10);
    centralHeader.writeUInt16LE(0, 12);
    centralHeader.writeUInt16LE(0, 14);
    centralHeader.writeUInt32LE(crc, 16);
    centralHeader.writeUInt32LE(dataBuf.length, 20);
    centralHeader.writeUInt32LE(dataBuf.length, 24);
    centralHeader.writeUInt16LE(nameBuf.length, 28);
    centralHeader.writeUInt16LE(0, 30);
    centralHeader.writeUInt16LE(0, 32);
    centralHeader.writeUInt16LE(0, 34);
    centralHeader.writeUInt16LE(0, 36);
    centralHeader.writeUInt32LE(0, 38);
    centralHeader.writeUInt32LE(offset, 42);
    nameBuf.copy(centralHeader, 46);

    localParts.push(localHeader, dataBuf);
    centralParts.push(centralHeader);
    offset += localHeader.length + dataBuf.length;
  }

  const centralBuffer = Buffer.concat(centralParts);
  const eocd = Buffer.alloc(22);
  eocd.writeUInt32LE(0x06054b50, 0);
  eocd.writeUInt16LE(0, 4);
  eocd.writeUInt16LE(0, 6);
  eocd.writeUInt16LE(allFiles.length, 8);
  eocd.writeUInt16LE(allFiles.length, 10);
  eocd.writeUInt32LE(centralBuffer.length, 12);
  eocd.writeUInt32LE(offset, 16);
  eocd.writeUInt16LE(0, 20);

  fs.writeFileSync(outputJarPath, Buffer.concat([...localParts, centralBuffer, eocd]));
}

async function main() {
  console.log('⏳ Đang chuẩn bị biên dịch nhân giả lập FreeJ2ME chuẩn Java 8 cho CheerpJ 3.0...');

  // 1. Tìm công cụ JDK trên máy
  const { javacPath, jarPath } = findJdkTools();
  console.log(`✅ Đã tìm thấy trình biên dịch Java: ${javacPath}`);

  // 2. Dọn thư mục tạm
  if (fs.existsSync(TEMP_DIR)) {
    fs.rmSync(TEMP_DIR, { recursive: true, force: true });
  }
  fs.mkdirSync(TEMP_DIR, { recursive: true });

  const zipPath = path.join(TEMP_DIR, 'freej2me-src.zip');
  let downloaded = false;

  for (const url of SOURCE_ZIP_URLS) {
    try {
      console.log(`🔄 Đang tải mã nguồn FreeJ2ME từ: ${url}`);
      const size = await downloadFile(url, zipPath);
      console.log(`⬇️ Đã tải xong mã nguồn (${Math.round(size / 1024)} KB).`);
      downloaded = true;
      break;
    } catch (err) {
      console.warn(`⚠️ Lỗi tải (${err.message}), thử link dự phòng...`);
    }
  }

  if (!downloaded) {
    throw new Error('Không thể tải mã nguồn FreeJ2ME từ GitHub.');
  }

  // 3. Giải nén mã nguồn
  const zip = new AdmZip(zipPath);
  zip.extractAllTo(TEMP_DIR, true);

  const extractedRoot = path.join(TEMP_DIR, 'freej2me-master');
  const srcRoot = path.join(extractedRoot, 'src');
  const classesDir = path.join(TEMP_DIR, 'classes');
  fs.mkdirSync(classesDir, { recursive: true });

  // 4. Vá lỗi trên mã nguồn trước khi biên dịch
  patchSourceFiles(srcRoot);

  // 5. Liệt kê toàn bộ file .java và tài nguyên đi kèm
  const allSrcFiles = getAllFiles(srcRoot);
  const javaFiles = allSrcFiles.filter((f) => f.endsWith('.java'));
  const resourceFiles = allSrcFiles.filter((f) => !f.endsWith('.java'));

  const sourcesListFile = path.join(TEMP_DIR, 'sources.txt');
  fs.writeFileSync(
    sourcesListFile,
    javaFiles.map((f) => `"${f.replace(/\\/g, '/')}"`).join('\n'),
    'utf8'
  );

  // 6. Biên dịch ra đúng chuẩn Java 8 (Bytecode v52.0) cho CheerpJ 3.0
  console.log(`⚙️ Đang biên dịch ${javaFiles.length} file .java sang Java 8 bytecode (v52.0)...`);
  try {
    execFileSync(
      javacPath,
      ['-source', '1.8', '-target', '1.8', '-encoding', 'UTF-8', '-nowarn', '-d', classesDir, `@${sourcesListFile}`],
      { stdio: 'inherit' }
    );
  } catch (_) {
    // Dự phòng nếu JDK mới yêu cầu cờ --release 8
    execFileSync(
      javacPath,
      ['--release', '8', '-encoding', 'UTF-8', '-nowarn', '-d', classesDir, `@${sourcesListFile}`],
      { stdio: 'inherit' }
    );
  }

  // 7. Copy các tài nguyên (font, icon, âm thanh mặc định) vào thư mục classes
  for (const resFile of resourceFiles) {
    const rel = path.relative(srcRoot, resFile);
    const dest = path.join(classesDir, rel);
    fs.mkdirSync(path.dirname(dest), { recursive: true });
    fs.copyFileSync(resFile, dest);
  }

  // Tạo META-INF/MANIFEST.MF chuẩn
  const manifestDir = path.join(classesDir, 'META-INF');
  fs.mkdirSync(manifestDir, { recursive: true });
  const manifestFile = path.join(manifestDir, 'MANIFEST.MF');
  fs.writeFileSync(
    manifestFile,
    'Manifest-Version: 1.0\r\nMain-Class: org.recompile.freej2me.FreeJ2ME\r\n\r\n',
    'utf8'
  );

  // 8. Đóng gói thành file public/lib/freej2me/freej2me.jar
  if (fs.existsSync(TARGET_FILE)) fs.unlinkSync(TARGET_FILE);

  try {
    execFileSync(jarPath, ['cfm', TARGET_FILE, manifestFile, '-C', classesDir, '.'], {
      stdio: 'inherit'
    });
    console.log('✅ Đã đóng gói bằng công cụ JDK jar chuẩn.');
  } catch (_) {
    writeStoredJarFile(classesDir, TARGET_FILE);
    console.log('✅ Đã đóng gói bằng trình tạo JAR STORED chuẩn.');
  }

  fs.writeFileSync(
    META_FILE,
    JSON.stringify(
      { argMode: 'url', compiledJavaVersion: 8, ioPatch: true, builtAt: new Date().toISOString() },
      null,
      2
    ),
    'utf8'
  );

  // Dọn thư mục tạm
  fs.rmSync(TEMP_DIR, { recursive: true, force: true });

  const finalSize = fs.statSync(TARGET_FILE).size;
  console.log(`🎉 HOÀN TẤT! File freej2me.jar (Java 8 v52.0) đã sẵn sàng: ${TARGET_FILE} (${Math.round(finalSize / 1024)} KB)`);
}

if (require.main === module) {
  main().catch((err) => {
    console.error('❌ Lỗi:', err.message);
    process.exit(1);
  });
}

module.exports = { patchSourceFiles, copyOverrides };