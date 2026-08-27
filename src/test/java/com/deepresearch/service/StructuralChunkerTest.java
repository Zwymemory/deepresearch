package com.deepresearch.service;

import com.deepresearch.model.KnowledgeChunk;
import com.deepresearch.model.ParsedDocument;
import com.deepresearch.model.ParsedSection;
import com.deepresearch.model.SourceType;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class StructuralChunkerTest {

    @Test
    void chunksLongSectionWithContextPrefix() {
        StructuralChunker chunker = new StructuralChunker(120, 20, 20);
        String text = "MCP-7788 是一个用于测试混合检索和精排的参数。".repeat(80);
        ParsedDocument document = new ParsedDocument(
                "参数手册",
                SourceType.TEXT,
                "params.txt",
                text,
                List.of(new ParsedSection("配置项 > MCP", null, text)));

        List<KnowledgeChunk> chunks = chunker.chunk("doc-test", document);

        assertThat(chunks).hasSizeGreaterThan(1);
        assertThat(chunks.get(0).content()).contains("文档：参数手册", "章节：配置项 > MCP", "正文：");
        assertThat(chunks)
                .extracting(KnowledgeChunk::chunkIndex)
                .containsExactlyElementsOf(java.util.stream.IntStream.range(0, chunks.size()).boxed().toList());
    }
}
