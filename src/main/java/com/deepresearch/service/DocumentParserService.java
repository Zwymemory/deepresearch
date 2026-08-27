package com.deepresearch.service;

import com.deepresearch.model.ParsedDocument;
import com.deepresearch.model.ParsedSection;
import com.deepresearch.model.SourceType;
import org.apache.pdfbox.Loader;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class DocumentParserService {

    private static final Pattern MARKDOWN_HEADING = Pattern.compile("^(#{1,6})\\s+(.+?)\\s*$");

    public ParsedDocument parseText(String title, String text) {
        String safeTitle = normalizeTitle(title, "未命名文本");
        String safeText = text == null ? "" : text;
        return new ParsedDocument(
                safeTitle,
                SourceType.TEXT,
                safeTitle + ".txt",
                safeText,
                List.of(new ParsedSection("正文", null, safeText)));
    }

    public ParsedDocument parseFile(String title, String filename, byte[] bytes) {
        String safeFilename = filename == null || filename.isBlank() ? "uploaded.txt" : filename;
        SourceType sourceType = SourceType.fromFilename(safeFilename);
        String safeTitle = normalizeTitle(title, stripExtension(safeFilename));
        return switch (sourceType) {
            case MARKDOWN -> parseMarkdown(safeTitle, safeFilename, bytes);
            case PDF -> parsePdf(safeTitle, safeFilename, bytes);
            case TEXT -> parsePlainText(safeTitle, safeFilename, bytes);
        };
    }

    public ParsedDocument parseStored(String title, SourceType sourceType, String filename, String rawContent) {
        String safeRaw = rawContent == null ? "" : rawContent;
        return switch (sourceType) {
            case MARKDOWN -> parseMarkdown(title, filename, safeRaw.getBytes(StandardCharsets.UTF_8));
            case PDF -> parseStoredPdfText(title, filename, safeRaw);
            case TEXT -> new ParsedDocument(title, SourceType.TEXT, filename, safeRaw,
                    List.of(new ParsedSection("正文", null, safeRaw)));
        };
    }

    private ParsedDocument parsePlainText(String title, String filename, byte[] bytes) {
        String text = new String(bytes, StandardCharsets.UTF_8);
        return new ParsedDocument(title, SourceType.TEXT, filename, text,
                List.of(new ParsedSection("正文", null, text)));
    }

    private ParsedDocument parseMarkdown(String title, String filename, byte[] bytes) {
        String text = new String(bytes, StandardCharsets.UTF_8);
        List<ParsedSection> sections = new ArrayList<>();
        String[] headings = new String[6];
        StringBuilder current = new StringBuilder();
        String currentPath = "正文";

        for (String line : text.split("\\R", -1)) {
            Matcher matcher = MARKDOWN_HEADING.matcher(line);
            if (matcher.matches()) {
                flushSection(sections, currentPath, current);
                int level = matcher.group(1).length();
                headings[level - 1] = matcher.group(2).trim();
                Arrays.fill(headings, level, headings.length, null);
                currentPath = sectionPath(headings);
                continue;
            }
            current.append(line).append('\n');
        }
        flushSection(sections, currentPath, current);
        if (sections.isEmpty()) {
            sections.add(new ParsedSection("正文", null, text));
        }
        return new ParsedDocument(title, SourceType.MARKDOWN, filename, text, sections);
    }

    private ParsedDocument parsePdf(String title, String filename, byte[] bytes) {
        List<ParsedSection> sections = new ArrayList<>();
        StringBuilder raw = new StringBuilder();
        try (PDDocument document = Loader.loadPDF(bytes)) {
            PDFTextStripper stripper = new PDFTextStripper();
            for (int page = 1; page <= document.getNumberOfPages(); page++) {
                stripper.setStartPage(page);
                stripper.setEndPage(page);
                String text = stripper.getText(document);
                raw.append("[[PAGE:").append(page).append("]]\n").append(text).append('\n');
                if (text != null && !text.isBlank()) {
                    sections.add(new ParsedSection("第" + page + "页", page, text));
                }
            }
        } catch (IOException e) {
            throw new IllegalArgumentException("PDF 解析失败: " + e.getMessage(), e);
        }
        if (sections.isEmpty()) {
            sections.add(new ParsedSection("正文", null, raw.toString()));
        }
        return new ParsedDocument(title, SourceType.PDF, filename, raw.toString(), sections);
    }

    private ParsedDocument parseStoredPdfText(String title, String filename, String rawContent) {
        List<ParsedSection> sections = new ArrayList<>();
        Pattern pageMarker = Pattern.compile("\\[\\[PAGE:(\\d+)]]\\n");
        Matcher matcher = pageMarker.matcher(rawContent);
        int lastStart = -1;
        int pageNumber = -1;
        while (matcher.find()) {
            if (lastStart >= 0) {
                addStoredPdfSection(sections, pageNumber, rawContent.substring(lastStart, matcher.start()));
            }
            pageNumber = Integer.parseInt(matcher.group(1));
            lastStart = matcher.end();
        }
        if (lastStart >= 0) {
            addStoredPdfSection(sections, pageNumber, rawContent.substring(lastStart));
        }
        if (sections.isEmpty()) {
            sections.add(new ParsedSection("正文", null, rawContent));
        }
        return new ParsedDocument(title, SourceType.PDF, filename, rawContent, sections);
    }

    private void addStoredPdfSection(List<ParsedSection> sections, int pageNumber, String text) {
        String clean = text == null ? "" : text.trim();
        if (!clean.isBlank()) {
            sections.add(new ParsedSection("第" + pageNumber + "页", pageNumber, clean));
        }
    }

    private void flushSection(List<ParsedSection> sections, String sectionPath, StringBuilder current) {
        String text = current.toString().trim();
        if (!text.isBlank()) {
            sections.add(new ParsedSection(sectionPath, null, text));
        }
        current.setLength(0);
    }

    private String sectionPath(String[] headings) {
        List<String> parts = Arrays.stream(headings)
                .filter(s -> s != null && !s.isBlank())
                .toList();
        return parts.isEmpty() ? "正文" : String.join(" > ", parts);
    }

    private String normalizeTitle(String title, String fallback) {
        if (title != null && !title.isBlank()) {
            return title.trim();
        }
        return fallback == null || fallback.isBlank() ? "未命名文档" : fallback.trim();
    }

    private String stripExtension(String filename) {
        int dot = filename.lastIndexOf('.');
        return dot <= 0 ? filename : filename.substring(0, dot);
    }
}
