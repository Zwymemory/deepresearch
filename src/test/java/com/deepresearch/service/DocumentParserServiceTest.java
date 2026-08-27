package com.deepresearch.service;

import com.deepresearch.model.ParsedDocument;
import com.deepresearch.model.SourceType;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.pdmodel.PDPage;
import org.apache.pdfbox.pdmodel.PDPageContentStream;
import org.apache.pdfbox.pdmodel.font.PDType1Font;
import org.apache.pdfbox.pdmodel.font.Standard14Fonts;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;

import static org.assertj.core.api.Assertions.assertThat;

class DocumentParserServiceTest {

    private final DocumentParserService parser = new DocumentParserService();

    @Test
    void parsesMarkdownSectionPath() {
        ParsedDocument document = parser.parseFile("员工手册", "employee.md", """
                # 休假制度
                ## 年假
                全职员工每年享有年假15天。
                ## 病假
                病假需要提交证明。
                """.getBytes());

        assertThat(document.sourceType()).isEqualTo(SourceType.MARKDOWN);
        assertThat(document.sections())
                .extracting("sectionPath")
                .contains("休假制度 > 年假", "休假制度 > 病假");
    }

    @Test
    void parsesPdfPages() throws Exception {
        byte[] pdf = createPdf("MCP-7788 parameter guide");

        ParsedDocument document = parser.parseFile("参数说明", "mcp.pdf", pdf);

        assertThat(document.sourceType()).isEqualTo(SourceType.PDF);
        assertThat(document.sections()).hasSize(1);
        assertThat(document.sections().get(0).pageNumber()).isEqualTo(1);
        assertThat(document.rawContent()).contains("MCP-7788");

        ParsedDocument restored = parser.parseStored(document.title(), SourceType.PDF, document.filename(), document.rawContent());
        assertThat(restored.sections().get(0).pageNumber()).isEqualTo(1);
        assertThat(restored.sections().get(0).text()).contains("MCP-7788");
    }

    private byte[] createPdf(String text) throws Exception {
        try (PDDocument document = new PDDocument();
             ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            PDPage page = new PDPage();
            document.addPage(page);
            try (PDPageContentStream content = new PDPageContentStream(document, page)) {
                content.beginText();
                content.setFont(new PDType1Font(Standard14Fonts.FontName.HELVETICA), 12);
                content.newLineAtOffset(50, 700);
                content.showText(text);
                content.endText();
            }
            document.save(out);
            return out.toByteArray();
        }
    }
}
