package com.asteria.server.tool;

import lombok.extern.slf4j.Slf4j;
import org.apache.pdfbox.pdmodel.PDDocument;
import org.apache.pdfbox.text.PDFTextStripper;
import org.springframework.stereotype.Component;

import java.io.IOException;
import java.nio.file.Path;

/**
 * 读 .pdf 的文本。
 *
 * <p>为什么需要它：PDF 是**二进制格式**（页面内容存在内容流里，还常带压缩），
 * 和 docx 一样不能按编码当纯文本读，必须用 PDFBox 抽取文字，再交给
 * {@link com.asteria.common.Tool.QuestionParser}。
 *
 * <p>⚠️ 局限：**扫描件（图片型 PDF）抽不出任何文字**。更麻烦的是它抽出来就是空字符串，
 * 和"这份 PDF 确实是空的"没法区分 —— 用户只会看到"解析出 0 道题"，不知道该改格式还是该检查文件。
 * 所以这里顺带做一次<b>文字层体检</b>（{@link Result#looksScanned()}），
 * 让上层能给出准确提示，而不是笼统的"识别不出题目"。
 */
@Component
@Slf4j
public class PdfTextReader {

    /**
     * 每页文字少于这个数，就认为"几乎没有文字层"。
     *
     * <p>取值依据：正常排版的题目 PDF，一页（A4）至少几百字；纯封面页可能有几十字。
     * 30 是很宽松的下限 —— 只用来区分"有文字"和"几乎没文字"，不追求精确。
     */
    private static final int MIN_CHARS_PER_PAGE = 30;

    /**
     * 读成字符串；读不了抛 IOException，由调用方翻译成业务异常。
     *
     * <p>只返回正文，不返回体检结果 —— 给"确实只要文字"的调用方用。
     */
    public String read(Path path) throws IOException {
        return readWithDiagnostics(path).text();
    }

    /**
     * 读文本，同时判断"像不像扫描件"。
     *
     * @param path PDF 路径
     * @return 文本 + 体检结果
     */
    public Result readWithDiagnostics(Path path) throws IOException {
        try (PDDocument document = PDDocument.load(path.toFile())) {
            int pageCount = document.getNumberOfPages();

            PDFTextStripper stripper = new PDFTextStripper();
            // 按页面坐标排序，尽量还原"从上到下、从左到右"的阅读顺序。
            // 注意：这对**单栏**文档有效；双栏文档 PDFBox 一直处理不好
            // （上游 issue PDFBOX-2637，2015 年提的，至今仍是 Open），属已知局限。
            stripper.setSortByPosition(true);

            String text = stripper.getText(document);
            if (text == null) {
                text = "";
            }
            // 去 BOM（和其它读取器保持一致）
            if (text.startsWith("\uFEFF")) {
                text = text.substring(1);
            }
            // 统一换行：\r\n 和单独的 \r 都变成 \n
            text = text.replace("\r\n", "\n").replace('\r', '\n');

            Result result = new Result(text, pageCount);
            if (result.looksScanned()) {
                log.warn("PDF 几乎没有文字层（{} 页共 {} 字）—— 很可能是扫描件，需要 OCR 或改用 txt/docx",
                        pageCount, text.length());
            }
            return result;
        }
    }

    /**
     * 读取结果 + 体检。
     *
     * @param text      抽取出来的正文
     * @param pageCount 页数
     */
    public record Result(String text, int pageCount) {

        /** 有没有抽出文字 */
        public boolean hasText() {
            return text != null && !text.isBlank();
        }

        /**
         * 像不像扫描件：有页面、但每页文字少得可怜。
         *
         * <p>这个判断只用来**给提示**，不用来拒绝导入：万一真是内容极少的 PDF，
         * 也只是多一句提醒，不会挡住正常流程。
         */
        public boolean looksScanned() {
            if (pageCount <= 0) {
                return false;
            }
            return text.length() / pageCount < MIN_CHARS_PER_PAGE;
        }

        /** 给用户看的一句话说明；正常时返回 null */
        public String scannedHint() {
            if (!looksScanned()) {
                return null;
            }
            return "这份 PDF 几乎没有文字层（" + pageCount + " 页只有 " + text.length()
                    + " 个字符），看起来是扫描件/图片版。图片里的文字无法直接提取，"
                    + "请改用原始的 txt / docx，或先用 OCR 转成文字后再导入。";
        }
    }
}
