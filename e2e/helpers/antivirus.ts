/** Build the standard antivirus test signature inside a DOCX archive, entirely in memory. */
export function infectedDocx(): Buffer {
  const signature = ['X5O!P%@AP[4', '\\PZX54(P^)7CC)7}', '$' + 'EICAR', '-STANDARD-ANTIVIRUS-TEST-FILE!', '$' + 'H+H*'].join('');
  if (signature.length !== 68) throw new Error('Некорректный тестовый образец антивируса');
  const entries = [
    ['[Content_Types].xml', '<Types xmlns="http://schemas.openxmlformats.org/package/2006/content-types"><Default Extension="xml" ContentType="application/xml"/></Types>'],
    ['word/document.xml', '<w:document xmlns:w="http://schemas.openxmlformats.org/wordprocessingml/2006/main"><w:body/></w:document>'],
    ['sample.com', signature]
  ];
  const local: Buffer[] = []; const directory: Buffer[] = []; let offset = 0;
  for (const [name, text] of entries) {
    const fileName = Buffer.from(name); const content = Buffer.from(text); const checksum = crc32(content);
    const header = Buffer.alloc(30); header.writeUInt32LE(0x04034b50); header.writeUInt16LE(20, 4);
    header.writeUInt32LE(checksum, 14); header.writeUInt32LE(content.length, 18); header.writeUInt32LE(content.length, 22); header.writeUInt16LE(fileName.length, 26);
    local.push(header, fileName, content);
    const central = Buffer.alloc(46); central.writeUInt32LE(0x02014b50); central.writeUInt16LE(20, 4); central.writeUInt16LE(20, 6);
    central.writeUInt32LE(checksum, 16); central.writeUInt32LE(content.length, 20); central.writeUInt32LE(content.length, 24);
    central.writeUInt16LE(fileName.length, 28); central.writeUInt32LE(offset, 42); directory.push(central, fileName);
    offset += header.length + fileName.length + content.length;
  }
  const index = Buffer.concat(directory); const end = Buffer.alloc(22); end.writeUInt32LE(0x06054b50);
  end.writeUInt16LE(entries.length, 8); end.writeUInt16LE(entries.length, 10); end.writeUInt32LE(index.length, 12); end.writeUInt32LE(offset, 16);
  return Buffer.concat([...local, index, end]);
}

function crc32(content: Buffer): number {
  let crc = 0xffffffff;
  for (const byte of content) { crc ^= byte; for (let bit = 0; bit < 8; bit++) crc = (crc >>> 1) ^ (crc & 1 ? 0xedb88320 : 0); }
  return (crc ^ 0xffffffff) >>> 0;
}
