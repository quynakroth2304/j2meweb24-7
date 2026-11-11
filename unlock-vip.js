// unlock-vip.js
const fs = require('fs');
const crypto = require('crypto');
const AdmZip = require('adm-zip');

const PATCH_MARKER_ENTRY = 'META-INF/.vip_patched_duytruong';

const STRING_REPLACEMENTS = [
  ['ThayGiaoNam.Com', 'Duy Trường'],
  ['Thaygiaonam.com', 'Duy Trường'],
  ['thaygiaonam.com', 'Duy Trường'],
  ['Thầy Giáo Nam', 'Duy Trường']
];

/**
 * Thay thế chuỗi UTF-8 trong Constant Pool của file .class Java một cách an toàn
 * (cập nhật đúng 2 byte độ dài u2 big-endian để không bao giờ gây lỗi ClassFormatError / hỏng Menu Mod).
 */
function replaceUtf8InClassBuffer(buf) {
  if (!buf || buf.length < 10 || buf.readUInt32BE(0) !== 0xCAFEBABE) {
    return { buffer: buf, changed: 0 };
  }
  const cpCount = buf.readUInt16BE(8);
  const chunks = [buf.subarray(0, 10)];
  let pos = 10;
  let changed = 0;

  for (let i = 1; i < cpCount; i++) {
    const tag = buf[pos];
    pos += 1;
    if (tag === 1) {
      const len = buf.readUInt16BE(pos);
      pos += 2;
      const raw = buf.subarray(pos, pos + len);
      pos += len;
      let str = raw.toString('utf8');
      let newStr = str;
      for (const [oldVal, newVal] of STRING_REPLACEMENTS) {
        if (newStr.includes(oldVal)) {
          newStr = newStr.split(oldVal).join(newVal);
        }
      }
      if (newStr !== str) {
        const newRaw = Buffer.from(newStr, 'utf8');
        const header = Buffer.alloc(3);
        header[0] = 1;
        header.writeUInt16BE(newRaw.length, 1);
        chunks.push(header, newRaw);
        changed++;
      } else {
        const header = Buffer.alloc(3);
        header[0] = 1;
        header.writeUInt16BE(raw.length, 1);
        chunks.push(header, raw);
      }
    } else if (tag === 7 || tag === 8 || tag === 16 || tag === 19 || tag === 20) {
      chunks.push(buf.subarray(pos - 1, pos + 2));
      pos += 2;
    } else if (tag === 3 || tag === 4 || tag === 9 || tag === 10 || tag === 11 || tag === 12 || tag === 17 || tag === 18) {
      chunks.push(buf.subarray(pos - 1, pos + 4));
      pos += 4;
    } else if (tag === 5 || tag === 6) {
      chunks.push(buf.subarray(pos - 1, pos + 8));
      pos += 8;
      i++;
    } else if (tag === 15) {
      chunks.push(buf.subarray(pos - 1, pos + 3));
      pos += 3;
    } else {
      return { buffer: buf, changed: 0 };
    }
  }

  if (changed === 0) {
    return { buffer: buf, changed: 0 };
  }
  chunks.push(buf.subarray(pos));
  return { buffer: Buffer.concat(chunks), changed };
}

function patchJarToVipAdmin(jarPath) {
  if (!jarPath || !fs.existsSync(jarPath)) {
    return { patched: false, count: 0, skipped: true };
  }

  const zip = new AdmZip(jarPath);
  if (zip.getEntry(PATCH_MARKER_ENTRY)) {
    return { patched: false, count: 0, skipped: true };
  }

  let patchCount = 0;
  const entries = zip.getEntries();

  for (const entry of entries) {
    if (entry.isDirectory) continue;
    if (entry.entryName.endsWith('.class')) {
      const data = entry.getData();
      const { buffer, changed } = replaceUtf8InClassBuffer(data);
      if (changed > 0) {
        zip.updateFile(entry.entryName, buffer);
        patchCount += changed;
      }
    } else if (entry.entryName === 'META-INF/MANIFEST.MF') {
      let txt = entry.getData().toString('utf8');
      let updated = txt;
      for (const [oldVal, newVal] of STRING_REPLACEMENTS) {
        updated = updated.split(oldVal).join(newVal);
      }
      if (updated !== txt) {
        zip.updateFile(entry.entryName, Buffer.from(updated, 'utf8'));
        patchCount++;
      }
    }
  }

  // Đồng bộ lại bảng băm META-INF/TGN-CATALOG.SHA256 để trình kiểm tra toàn vẹn luôn hợp lệ
  const catalogEntry = zip.getEntry('META-INF/TGN-CATALOG.SHA256');
  if (catalogEntry && patchCount > 0) {
    const lines = catalogEntry.getData().toString('utf8').split('\n');
    const updatedLines = lines.map((line) => {
      if (line.length >= 67 && line.slice(64, 66) === '  ') {
        const entryPath = line.slice(66).replace(/\r$/, '');
        const targetEntry = zip.getEntry(entryPath);
        if (targetEntry) {
          const hash = crypto.createHash('sha256').update(targetEntry.getData()).digest('hex');
          return `${hash}  ${entryPath}`;
        }
      }
      return line;
    });
    zip.updateFile('META-INF/TGN-CATALOG.SHA256', Buffer.from(updatedLines.join('\n'), 'utf8'));
  }

  zip.addFile(PATCH_MARKER_ENTRY, Buffer.from(`DuyTruong-VIP-ADMIN:${new Date().toISOString()}`, 'utf8'));

  const tmpPath = `${jarPath}.tmp.${process.pid}.${Date.now()}`;
  zip.writeZip(tmpPath);
  fs.renameSync(tmpPath, jarPath);

  if (patchCount > 0) {
    console.log(`[UnlockVIP - Duy Trường] Đã mở khóa VIP ADMIN & đổi tên (${patchCount} vị trí) cho ${jarPath}`);
  }
  return { patched: patchCount > 0, count: patchCount, skipped: false };
}

module.exports = { patchJarToVipAdmin };