package service

import model.TextElement
import org.apache.poi.xwpf.usermodel.XWPFDocument

import java.io.{File, FileInputStream, FileOutputStream}
import scala.jdk.CollectionConverters.*
import scala.util.{Try, Using}

/** Handles reading text from and writing translated text back to docx files. */
object DocxService extends DocumentFormat:

  override val extensions: Seq[String] = Seq("docx")

  override def extractTexts(file: File): Either[String, Seq[TextElement]] =
    Try {
      Using.resource(new XWPFDocument(FileInputStream(file))) { doc =>
        extractBodyParagraphs(doc) ++ extractTableCells(doc)
      }
    }.toEither.left.map(e => s"Failed to read docx: ${e.getMessage}")

  override def writeTranslated(
      source: File,
      elements: Seq[TextElement],
      outputFile: File
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
          lookup.get(key).foreach(run.setText)

        // Write table cells
        for
          (table, ti) <- doc.getTables.asScala.toSeq.zipWithIndex
          (row, ri)   <- table.getRows.asScala.toSeq.zipWithIndex
          (cell, ci)  <- row.getTableCells.asScala.toSeq.zipWithIndex
          (para, pi)  <- cell.getParagraphs.asScala.toSeq.zipWithIndex
          (run, rui)  <- para.getRuns.asScala.toSeq.zipWithIndex
        do
          val key = (ci, ti, Some(ri), pi, rui)
          lookup.get(key).foreach(run.setText)

        doc.write(out)
      }
    }.toEither.left.map(e => s"Failed to write docx: ${e.getMessage}")

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

end DocxService
