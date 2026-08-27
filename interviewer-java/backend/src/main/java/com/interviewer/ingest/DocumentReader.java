package com.interviewer.ingest;

import com.interviewer.core.error.ResumeParseException;
import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.apache.poi.xwpf.usermodel.XWPFDocument;
import org.apache.poi.xwpf.usermodel.XWPFParagraph;
import org.apache.poi.xwpf.usermodel.XWPFTable;
import org.apache.poi.xwpf.usermodel.XWPFTableCell;
import org.apache.poi.xwpf.usermodel.XWPFTableRow;
import org.springframework.stereotype.Component;

/**
 * 把简历/JD 文件读成纯文本。只做提取，不做任何语义处理。
 *
 * <p>PDFBox 与 POI 替代原版的 pdfplumber 与 python-docx——这一块 Java 生态更成熟，
 * 是整个移植里少数变简单的地方。
 */
@Component
public class DocumentReader {

    public static final List<String> SUPPORTED = List.of(".pdf", ".docx", ".txt", ".md");

    private static final int MAX_CHARS = 60_000;

    /** 纯文本文件的编码猜测顺序。国内简历里 GBK 仍然常见。 */
    private static final List<Charset> ENCODINGS = List.of(
            StandardCharsets.UTF_8, Charset.forName("GBK"), Charset.forName("GB18030"));

    public String read(Path path) {
        if (!Files.exists(path)) {
            throw new ResumeParseException("文件不存在: " + path);
        }
        String name = path.getFileName().toString();
        String suffix = suffixOf(name);
        String text = switch (suffix) {
            case ".pdf" -> readPdf(path);
            case ".docx" -> readDocx(path);
            case ".txt", ".md" -> readPlain(path);
            default -> throw new ResumeParseException("不支持的文件类型: " + suffix);
        };
        String cleaned = normalize(text);
        if (cleaned.isEmpty()) {
            throw new ResumeParseException(
                    "未能从 " + name + " 中提取到文字，可能是扫描件或纯图片");
        }
        return cleaned.length() <= MAX_CHARS ? cleaned : cleaned.substring(0, MAX_CHARS);
    }

    private static String suffixOf(String name) {
        int dot = name.lastIndexOf('.');
        return dot < 0 ? "" : name.substring(dot).toLowerCase(Locale.ROOT);
    }

    private static String readPdf(Path path) {
        try (PDDocument doc = Loader.loadPDF(path.toFile())) {
            PDFTextStripper stripper = new PDFTextStripper();
            // 按页面视觉顺序抽取，多栏简历不至于把左右栏交错在一起
            stripper.setSortByPosition(true);
            return stripper.getText(doc);
        } catch (Exception e) {
            throw new ResumeParseException("PDF 解析失败: " + e.getMessage(), e);
        }
    }

    private static String readDocx(Path path) {
        try (XWPFDocument doc = new XWPFDocument(Files.newInputStream(path))) {
            List<String> parts = new ArrayList<>();
            for (XWPFParagraph p : doc.getParagraphs()) {
                parts.add(p.getText());
            }
            // 表格里常放技能矩阵与时间线，漏了会丢掉半份简历
            for (XWPFTable table : doc.getTables()) {
                for (XWPFTableRow row : table.getRows()) {
                    List<String> cells = new ArrayList<>();
                    for (XWPFTableCell cell : row.getTableCells()) {
                        cells.add(cell.getText() == null ? "" : cell.getText().strip());
                    }
                    parts.add(String.join(" | ", cells));
                }
            }
            return String.join("\n", parts);
        } catch (Exception e) {
            throw new ResumeParseException("DOCX 解析失败: " + e.getMessage(), e);
        }
    }

    private static String readPlain(Path path) {
        byte[] bytes;
        try {
            bytes = Files.readAllBytes(path);
        } catch (IOException e) {
            throw new ResumeParseException("读取失败: " + e.getMessage(), e);
        }
        for (Charset charset : ENCODINGS) {
            String decoded = tryDecode(bytes, charset);
            if (decoded != null) {
                return decoded;
            }
        }
        throw new ResumeParseException("无法识别 " + path.getFileName() + " 的文本编码");
    }

    /** 严格解码，失败返回 null 让调用方试下一种编码。 */
    private static String tryDecode(byte[] bytes, Charset charset) {
        try {
            var decoder = charset.newDecoder();
            return decoder.decode(java.nio.ByteBuffer.wrap(bytes)).toString();
        } catch (Exception e) {
            return null;
        }
    }

    /** 压掉连续空行并去掉行尾空白，避免把版式空白当成内容喂给模型。 */
    private static String normalize(String text) {
        String unified = text.replace("\r\n", "\n").replace('\r', '\n');
        List<String> out = new ArrayList<>();
        int blank = 0;
        for (String line : unified.split("\n", -1)) {
            String trimmed = stripTrailing(line);
            if (!trimmed.isBlank()) {
                blank = 0;
                out.add(trimmed);
            } else {
                blank++;
                if (blank <= 1) {
                    out.add("");
                }
            }
        }
        return String.join("\n", out).strip();
    }

    private static String stripTrailing(String line) {
        int end = line.length();
        while (end > 0 && Character.isWhitespace(line.charAt(end - 1))) {
            end--;
        }
        return line.substring(0, end);
    }
}
