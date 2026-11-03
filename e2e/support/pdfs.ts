/** Builds real PDF files, with one page per text, so the repository needs no binary fixtures. */
export function pdf(pageTexts: readonly string[]): Buffer {
  const escape = (text: string): string => text.replace(/\\/g, '\\\\').replace(/\(/g, '\\(').replace(/\)/g, '\\)');
  const kids = pageTexts.map((_, index) => `${4 + 2 * index} 0 R`).join(' ');
  const objects = [
    '<< /Type /Catalog /Pages 2 0 R >>',
    `<< /Type /Pages /Kids [${kids}] /Count ${pageTexts.length} >>`,
    '<< /Type /Font /Subtype /Type1 /BaseFont /Helvetica /Encoding /WinAnsiEncoding >>',
  ];
  pageTexts.forEach((text, index) => {
    const content = text === '' ? '' : `BT /F1 10 Tf 40 760 Td 12 TL (${escape(text)}) Tj ET`;
    objects.push(
      `<< /Type /Page /Parent 2 0 R /MediaBox [0 0 612 792] /Resources << /Font << /F1 3 0 R >> >> /Contents ${5 + 2 * index} 0 R >>`,
      `<< /Length ${Buffer.byteLength(content, 'latin1')} >>\nstream\n${content}\nendstream`,
    );
  });

  let file = '%PDF-1.7\n';
  const offsets: number[] = [];
  objects.forEach((body, index) => {
    offsets.push(Buffer.byteLength(file, 'latin1'));
    file += `${index + 1} 0 obj\n${body}\nendobj\n`;
  });
  const xref = Buffer.byteLength(file, 'latin1');
  file += `xref\n0 ${objects.length + 1}\n0000000000 65535 f \n`;
  offsets.forEach((offset) => (file += `${String(offset).padStart(10, '0')} 00000 n \n`));
  file += `trailer\n<< /Size ${objects.length + 1} /Root 1 0 R >>\nstartxref\n${xref}\n%%EOF\n`;
  return Buffer.from(file, 'latin1');
}

/** Each page has its own subject, so the page an answer cites shows that the right passage was found. */
export const CONTRACT = pdf([
  'Service agreement between the provider and the customer for the use of the Orchid management system.',
  'Clause 7. The contract can be terminated by either party with a notice period of 60 days. Termination without notice incurs a penalty of 20 percent of the remaining fees.',
  'Clause 9. The monthly fee is 4500 reais, adjusted every year, and it is due on day 10 of each month.',
  'Clause 12. Any dispute about this contract goes to the courts of the city of Curitiba.',
]);

export const HANDBOOK = pdf([
  'Employee handbook. Employees are entitled to thirty days of paid vacation per year, scheduled in advance with their manager.',
  'Remote work is allowed for up to two days per week, as agreed with the manager.',
]);

/** Has the PDF signature, and nothing else that a reader could use. */
export const CORRUPT = Buffer.from('%PDF-1.7\nthis is not really a PDF', 'latin1');

/** A valid PDF whose pages have no text, as a scanned document would look to the extractor. */
export const NO_TEXT = pdf(['', '']);
