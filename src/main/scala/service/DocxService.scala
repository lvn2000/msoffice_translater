package service

import model.TextElement
import org.apache.poi.xwpf.usermodel.{XWPFDocument, XWPFHeaderFooter, XWPFRun}

import java.io.{File, FileInputStream, FileOutputStream}
import scala.jdk.CollectionConverters.*
import scala.util.{Try, Using}

/** Handles reading text from and writing translated text back to docx files. */
object DocxService extends DocumentFormat:

  override val extensions: Seq[String] = Seq("docx")

  override def extractTexts(file: File): Either[String, Seq[TextElement]] =
    Try {
      Using.resource(new XWPFDocument(FileInputStream(file))) { doc =>
        extractBodyParagraphs(doc) ++
        extractTableCells(doc) ++
        extractHeaders(doc) ++
        extractFooters(doc)
      }
    }.toEither.left.map(e => s"Failed to read docx: ${e.getMessage}")

  override def writeTranslated(
      source: File,
      elements: Seq[TextElement],
      outputFile: File,
      langCode: String
  ): Either[String, Unit] =
    val lookup = DocumentService.buildLookup(elements)
    Try {
      Using.resources(
        new XWPFDocument(FileInputStream(source)),
        new FileOutputStream(outputFile)
      ) { (doc, out) =>
        // Write body paragraphs
        for
          (para, pi) <- doc.getParagraphs.asScala.toSeq.zipWithIndex
          (run, ri)  <- para.getRuns.asScala.toSeq.zipWithIndex
        do
          val key = (0, 0, None, pi, ri)
          lookup.get(key).foreach(t => setRunText(run, t, langCode))

        // Write table cells
        for
          (table, ti) <- doc.getTables.asScala.toSeq.zipWithIndex
          (row, ri)   <- table.getRows.asScala.toSeq.zipWithIndex
          (cell, ci)  <- row.getTableCells.asScala.toSeq.zipWithIndex
          (para, pi)  <- cell.getParagraphs.asScala.toSeq.zipWithIndex
          (run, rui)  <- para.getRuns.asScala.toSeq.zipWithIndex
        do
          val key = (ci, ti, Some(ri), pi, rui)
          lookup.get(key).foreach(t => setRunText(run, t, langCode))

        // Write headers
        for
          (header, hi) <- doc.getHeaderList.asScala.toSeq.zipWithIndex
          (para, pi)   <- header.getParagraphs.asScala.toSeq.zipWithIndex
          (run, ri)    <- para.getRuns.asScala.toSeq.zipWithIndex
        do
          val key = (-1, hi, None, pi, ri)
          lookup.get(key).foreach(t => setRunText(run, t, langCode))

        // Write footers
        for
          (footer, fi) <- doc.getFooterList.asScala.toSeq.zipWithIndex
          (para, pi)   <- footer.getParagraphs.asScala.toSeq.zipWithIndex
          (run, ri)    <- para.getRuns.asScala.toSeq.zipWithIndex
        do
          val key = (-2, fi, None, pi, ri)
          lookup.get(key).foreach(t => setRunText(run, t, langCode))

        // Set default document language in styles
        setDocDefaultLanguage(doc, langCode)

        doc.write(out)
      }
    }.toEither.left.map(e => s"Failed to write docx: ${e.getMessage}")

  /** Replace text in an XWPF run, clearing all previous text segments
    * and setting the run language to the target language code.
    */
  private def setRunText(run: XWPFRun, text: String, langCode: String): Unit =
    if text == null then return
    val ctRun = run.getCTR
    while ctRun.sizeOfTArray > 0 do
      ctRun.removeT(0)
    ctRun.addNewT.setStringValue(text)

    // Set run-level language to target language
    val wNs = "http://schemas.openxmlformats.org/wordprocessingml/2006/main"
    val rPr = if ctRun.getRPr != null then ctRun.getRPr else ctRun.addNewRPr()
    val c = rPr.newCursor()
    if c.toChild(wNs, "lang") then c.removeXml()
    c.toEndToken()
    c.insertElement("lang", wNs)
    c.toPrevToken()
    c.insertAttributeWithValue("val", wNs, langCode)
    c.dispose()

  /** Set the default document language in styles (e.g. "ru-RU", "uk-UA").
    *
    * Word uses the <w:lang> element in default run properties to determine
    * the proofing language for the document.
    */
  private def setDocDefaultLanguage(doc: XWPFDocument, langCode: String): Unit =
    import org.openxmlformats.schemas.wordprocessingml.x2006.main.StylesDocument

    val stylesPkgPart = doc.getStyles.getPackagePart
    val is = stylesPkgPart.getInputStream
    val stylesDoc = StylesDocument.Factory.parse(is)
    is.close()
    val ctStyles = stylesDoc.getStyles

    val docDefaults = if ctStyles.getDocDefaults != null then ctStyles.getDocDefaults
                      else ctStyles.addNewDocDefaults()
    val rPrDefault = if docDefaults.getRPrDefault != null then docDefaults.getRPrDefault
                     else docDefaults.addNewRPrDefault()
    val rPr = if rPrDefault.getRPr != null then rPrDefault.getRPr
              else rPrDefault.addNewRPr()

    // Add w:lang via XmlCursor
    val wNs = "http://schemas.openxmlformats.org/wordprocessingml/2006/main"
    val c = rPr.newCursor()
    if c.toChild(wNs, "lang") then c.removeXml()
    c.toEndToken()
    c.insertElement("lang", wNs)
    c.toPrevToken()
    c.insertAttributeWithValue("val", wNs, langCode)
    c.dispose()

    val os = stylesPkgPart.getOutputStream
    stylesDoc.save(os)
    os.close()

  // --------------------------------------------------------------------------
  // Private helpers
  // --------------------------------------------------------------------------

  private def extractBodyParagraphs(doc: XWPFDocument): Seq[TextElement] =
    for
      (para, pi) <- doc.getParagraphs.asScala.to(LazyList).zipWithIndex
      if para.getRuns != null
      (run, ri)  <- para.getRuns.asScala.to(LazyList).zipWithIndex
      text = run.getText(0)
      if text != null && text.trim.nonEmpty
    yield TextElement(
      slideIndex     = 0,
      shapeIndex     = 0,
      tableRowIndex  = None,
      paragraphIndex = pi,
      runIndex       = ri,
      originalText   = text
    )

  private def extractRunsFrom(hf: XWPFHeaderFooter): Seq[(Int, Int, String)] =
    for
      (para, pi) <- hf.getParagraphs.asScala.to(LazyList).zipWithIndex
      if para.getRuns != null
      (run, ri)  <- para.getRuns.asScala.to(LazyList).zipWithIndex
      text = run.getText(0)
      if text != null && text.trim.nonEmpty
    yield (pi, ri, text)

  private def extractTableCells(doc: XWPFDocument): Seq[TextElement] =
    for
      (table, ti) <- doc.getTables.asScala.to(LazyList).zipWithIndex
      (row, ri)   <- table.getRows.asScala.to(LazyList).zipWithIndex
      (cell, ci)  <- row.getTableCells.asScala.to(LazyList).zipWithIndex
      (para, pi)  <- cell.getParagraphs.asScala.to(LazyList).zipWithIndex
      (run, rui)  <- para.getRuns.asScala.to(LazyList).zipWithIndex
      text = run.getText(0)
      if text != null && text.trim.nonEmpty
    yield TextElement(
      slideIndex     = ci,
      shapeIndex     = ti,
      tableRowIndex  = Some(ri),
      paragraphIndex = pi,
      runIndex       = rui,
      originalText   = text
    )

  private def extractHeaders(doc: XWPFDocument): Seq[TextElement] =
    for
      (header, hi) <- doc.getHeaderList.asScala.to(LazyList).zipWithIndex
      (pi, ri, text) <- extractRunsFrom(header)
    yield TextElement(
      slideIndex     = -1,
      shapeIndex     = hi,
      tableRowIndex  = None,
      paragraphIndex = pi,
      runIndex       = ri,
      originalText   = text
    )

  private def extractFooters(doc: XWPFDocument): Seq[TextElement] =
    for
      (footer, fi) <- doc.getFooterList.asScala.to(LazyList).zipWithIndex
      (pi, ri, text) <- extractRunsFrom(footer)
    yield TextElement(
      slideIndex     = -2,
      shapeIndex     = fi,
      tableRowIndex  = None,
      paragraphIndex = pi,
      runIndex       = ri,
      originalText   = text
    )

end DocxService
