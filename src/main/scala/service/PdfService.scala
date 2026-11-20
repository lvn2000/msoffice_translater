package service

import model.TextElement
import org.apache.pdfbox.Loader
import org.apache.pdfbox.pdmodel.{PDDocument, PDPageContentStream}
import org.apache.pdfbox.pdmodel.font.{PDType0Font, PDType1Font, PDFont, Standard14Fonts}
import org.apache.pdfbox.text.{PDFTextStripper, TextPosition}

import java.io.{ByteArrayOutputStream, File}
import java.util.{List as JList}
import scala.collection.mutable
import scala.jdk.CollectionConverters.*
import scala.util.{Try, Using}

/** Handles reading text from and writing translated text back to pdf files.
  *
  * Unlike Office formats, a PDF has no editable text model. To keep the
  * document intact — images, figures and page layout — the translated text is
  * drawn *on top of the original pages* (AppendMode.APPEND): each translated
  * line is overpainted with a white rectangle and the translation is drawn at
  * the original position. Monospaced (code) text is detected and left
  * untouched, so program listings keep their exact formatting.
  */
object PdfService extends DocumentFormat:

  override val extensions: Seq[String] = Seq("pdf")

  override def unitName: String = "page"

  /** A text line plus the geometry needed to redraw it in place. */
  private case class LineInfo(
      text: String,
      x: Float,
      top: Float,      // distance from the top of the page (TextPosition.getYDirAdj)
      width: Float,
      height: Float,
      fontSize: Float,
      codeLike: Boolean,
      horizontal: Boolean
  ):
    /** Lines worth translating: horizontal, not code, containing letters. */
    def translatable: Boolean =
      horizontal && !codeLike && text.exists(_.isLetter)

  /** PDFTextStripper that records glyph positions, then groups them into lines
    * and splits each line into columns at page-wide vertical gutters. This is
    * essential for multi-column tables: otherwise the left and right cells of a
    * row collapse into a single "line" (and code would swallow the prose).
    */
  private class LineCollector extends PDFTextStripper:
    private val raw   = mutable.Map.empty[Int, mutable.ArrayBuffer[TextPosition]]
    private val built = mutable.Map.empty[Int, Seq[LineInfo]]

    override def writeString(text: String, textPositions: JList[TextPosition]): Unit =
      if textPositions != null && !textPositions.isEmpty then
        raw.getOrElseUpdate(getCurrentPageNo - 1, mutable.ArrayBuffer.empty) ++=
          textPositions.asScala

    def linesFor(pageIndex: Int): Seq[LineInfo] =
      if built.isEmpty && raw.nonEmpty then
        for (pg, tps) <- raw do built(pg) = buildPage(tps.toSeq)
      built.getOrElse(pageIndex, Seq.empty)

    private def buildPage(tps: Seq[TextPosition]): Seq[LineInfo] =
      if tps.isEmpty then Seq.empty
      else
        val gutters = detectGutters(tps)
        tps.groupBy(tp => math.round(tp.getYDirAdj)).toSeq.sortBy(_._1).flatMap {
          (_, glyphs) =>
            val sorted = glyphs.sortBy(_.getXDirAdj)
            val segments = mutable.ArrayBuffer.empty[mutable.ArrayBuffer[TextPosition]]
            var current  = mutable.ArrayBuffer.empty[TextPosition]
            var currentCol = Int.MinValue
            for tp <- sorted do
              val col = gutters.count { case (_, g1) => tp.getXDirAdj >= g1 }
              if current.nonEmpty && col != currentCol then
                segments += current
                current = mutable.ArrayBuffer.empty[TextPosition]
              current += tp
              currentCol = col
            if current.nonEmpty then segments += current
            segments.toSeq.flatMap(toLineInfo)
        }

    private def toLineInfo(seg: mutable.ArrayBuffer[TextPosition]): Option[LineInfo] =
      val segText = seg.map(tp => Option(tp.getUnicode).getOrElse("")).mkString.trim
      if segText.isEmpty then None
      else
        val xs      = seg.map(_.getXDirAdj)
        val ys      = seg.map(_.getYDirAdj)
        val rights  = seg.map(tp => tp.getXDirAdj + tp.getWidthDirAdj)
        val bottoms = seg.map(tp => tp.getYDirAdj + tp.getHeightDir)
        val sfs     = seg.map(_.getFontSizeInPt).max
        val horizontal = seg.forall(tp => math.abs(tp.getDir) < 0.1f)
        Some(LineInfo(
          text       = segText,
          x          = xs.min,
          top        = ys.min,
          width      = rights.max - xs.min,
          height     = bottoms.max - ys.min,
          fontSize   = if sfs > 0f then sfs else 10f,
          codeLike   = isCodeLike(segText),
          horizontal = horizontal
        ))

    /** Detect vertical gutters: x-ranges that almost no text line crosses. */
    private def detectGutters(tps: Seq[TextPosition]): Seq[(Float, Float)] =
      val minX = tps.map(_.getXDirAdj).min
      val maxX = tps.map(tp => tp.getXDirAdj + tp.getWidthDirAdj).max
      val lo   = math.floor(minX).toInt
      val hi   = math.ceil(maxX).toInt
      if hi <= lo then Seq.empty
      else
        val cover = Array.fill(hi - lo + 1)(0)
        for tp <- tps do
          val a = math.max(lo, math.floor(tp.getXDirAdj).toInt)
          val b = math.min(hi, math.ceil(tp.getXDirAdj + tp.getWidthDirAdj).toInt)
          var x = a
          while x <= b do
            cover(x - lo) += 1
            x += 1
        val lineCount = tps.map(tp => math.round(tp.getYDirAdj)).distinct.size
        val thr = math.max(1, lineCount / 12)
        val gutters = mutable.ArrayBuffer.empty[(Float, Float)]
        var start = -1
        for i <- cover.indices do
          val low = cover(i) <= thr
          if low && start < 0 then start = i
          if (!low || i == cover.length - 1) && start >= 0 then
            val end = if low then i else i - 1
            val g0  = (start + lo).toFloat
            val g1  = (end + lo).toFloat
            if (g1 - g0) >= 3f && g0 > minX + 5f && g1 < maxX - 5f then
              gutters += ((g0, g1))
            start = -1
        gutters.toSeq

  override def extractTexts(file: File, maxUnits: Int): Either[String, Seq[TextElement]] =
    Try {
      Using.resource(Loader.loadPDF(file)) { doc =>
        val totalPages = doc.getNumberOfPages
        val pageCount  = if maxUnits > 0 then math.min(totalPages, maxUnits) else totalPages

        val collector = collect(doc, pageCount)

        (0 until pageCount).flatMap { pageIndex =>
          collector.linesFor(pageIndex).zipWithIndex.collect {
            case (line, lineIndex) if line.translatable =>
              TextElement(
                slideIndex     = pageIndex,
                shapeIndex     = lineIndex,
                tableRowIndex  = None,
                paragraphIndex = 0,
                runIndex       = 0,
                originalText   = line.text.trim
              )
          }
        }
      }
    }.toEither.left.map(e => s"Failed to read pdf: ${e.getMessage}")

  override def writeTranslated(
      source: File,
      elements: Seq[TextElement],
      outputFile: File,
      langCode: String
  ): Either[String, Unit] =
    // (page, line) -> translated text
    val translations: Map[(Int, Int), String] =
      elements.map(e => (e.slideIndex, e.shapeIndex) -> e.translatedText).toMap

    val result = Try {
      Using.resource(Loader.loadPDF(source)) { doc =>
        val font       = loadUnicodeFont(doc)
        val totalPages = doc.getNumberOfPages
        val collector  = collect(doc, totalPages)

        val pagesToUpdate = translations.keys.map(_._1).toSet.toSeq.sorted
        for pageIndex <- pagesToUpdate if pageIndex < totalPages do
          val page = doc.getPage(pageIndex)
          if page.getRotation == 0 then
            val box         = page.getMediaBox
            val pageHeight  = box.getHeight
            val lines       = collector.linesFor(pageIndex)

            if lines.nonEmpty then
              Using.resource(
                new PDPageContentStream(
                  doc, page,
                  PDPageContentStream.AppendMode.APPEND, true, true
                )
              ) { stream =>
                for (line, lineIndex) <- lines.zipWithIndex do
                  translations.get((pageIndex, lineIndex)).filter(_.nonEmpty).foreach {
                    translated =>
                      val text = sanitize(translated)
                      if text.nonEmpty && line.width > 0f && line.height > 0f then
                        // Keep the translation within the original line's width so
                        // it stays inside its table cell / column.
                        val maxWidth = line.width + 2f
                        val size     = fitSize(font, text, maxWidth, line.fontSize)
                        // size == -1 means the font cannot render this text
                        if size > 0f then
                          // getYDirAdj is the baseline, measured from the top of
                          // the page. Glyphs rise ~0.70*fontSize above it and drop
                          // below only when the line actually has descenders.
                          val fs        = line.fontSize
                          val baselineY = pageHeight - line.top
                          val hasDesc   = line.text.exists(c => "gjpqyQ,;()[]{}|/".indexOf(c) >= 0)
                          val ascent    = 0.70f * fs
                          val descent   = if hasDesc then 0.20f * fs else 0.04f * fs
                          // Tight overpaint: stays clear of the table grid rules
                          // that run through the gaps between rows.
                          val coverTopTD    = line.top - ascent
                          val coverBottomTD = line.top + descent
                          val coverBottomY  = pageHeight - coverBottomTD
                          val coverHeight   = coverBottomTD - coverTopTD
                          // 1) overpaint the original line
                          stream.setNonStrokingColor(1f) // white
                          stream.addRect(line.x, coverBottomY, line.width + 0.3f, coverHeight)
                          stream.fill()
                          // 2) draw the translation at the same baseline
                          stream.beginText()
                          stream.setFont(font, size)
                          stream.setNonStrokingColor(0f) // black
                          stream.newLineAtOffset(line.x, baselineY)
                          stream.showText(text)
                          stream.endText()
                  }
              }

        val bos = new ByteArrayOutputStream()
        doc.save(bos)
        bos.close()
        java.nio.file.Files.write(outputFile.toPath, bos.toByteArray)
      }
    }.toEither.left.map(e => s"Failed to write pdf: ${e.getMessage}").map(_ => ())

    if result.isLeft && outputFile.exists && outputFile.length == 0 then
      outputFile.delete()

    result

  // --------------------------------------------------------------------------
  // Private helpers
  // --------------------------------------------------------------------------

  /** Run the position collector over the first `pageCount` pages. */
  private def collect(doc: PDDocument, pageCount: Int): LineCollector =
    val collector = new LineCollector
    collector.setSortByPosition(true)
    collector.setStartPage(1)
    collector.setEndPage(math.max(1, pageCount))
    collector.getText(doc) // triggers processing; output text is ignored
    collector

  /** Font size that makes `text` fit `maxWidth`, capped at `preferred`.
    * Returns -1 when the font cannot render the text (missing glyph).
    */
  private def fitSize(font: PDFont, text: String, maxWidth: Float, preferred: Float): Float =
    val unitWidth =
      try font.getStringWidth(text) / 1000f
      catch case _: Exception => return -1f
    if unitWidth <= 0f then -1f
    else
      val fitted = if maxWidth > 0f then maxWidth / unitWidth else preferred
      math.max(4f, math.min(preferred, fitted))

  /** Remove control characters that cannot be drawn. */
  private def sanitize(s: String): String =
    s.map(c => if c < ' ' then ' ' else c).trim

  private def isCodeLike(text: String): Boolean =
    val s = text.trim
    if s.isEmpty then false
    else
      val lower = s.toLowerCase
      val symbolRatio =
        s.count(c => "{}[]();=<>+*/&|!#~^%".contains(c)).toDouble / s.length
      s.startsWith("#") ||                                  // preprocessor
        s.endsWith("{") || s.endsWith("}") ||               // block braces
        (s.endsWith(";") &&                                // statement
          (s.contains('=') || s.contains('(') || s.contains(')') || s.contains('['))) ||
        s.contains("->") || s.contains("::") || lower.contains("std::") ||
        (symbolRatio > 0.15 && (s.contains('=') || s.contains(';'))) ||
        CodeTypeKeyword.findFirstIn(lower).isDefined

  /** Type/function keywords that reliably indicate a C statement. */
  private val CodeTypeKeyword =
    """\b(int|char|float|void|struct|unsigned|typedef|sizeof|printf|scanf|malloc|uint|bool)\b""".r

  /** Find and load a Unicode-capable TrueType font, falling back to Helvetica. */
  private def loadUnicodeFont(doc: PDDocument): PDFont =
    val candidates = Seq(
      "/usr/share/fonts/truetype/dejavu/DejaVuSans.ttf",
      "/usr/share/fonts/truetype/liberation/LiberationSans-Regular.ttf",
      "/usr/share/fonts/truetype/noto/NotoSans-Regular.ttf",
      "/usr/share/fonts/truetype/droid/DroidSans.ttf",
      "/System/Library/Fonts/Supplemental/Arial.ttf",
      "/Library/Fonts/Arial.ttf",
      "C:\\Windows\\Fonts\\arial.ttf"
    )
    candidates.find(p => new File(p).exists()) match
      case Some(path) =>
        try PDType0Font.load(doc, new File(path))
        catch case _: Exception => new PDType1Font(Standard14Fonts.FontName.HELVETICA)
      case None => new PDType1Font(Standard14Fonts.FontName.HELVETICA)

end PdfService
