package service

import model.TextElement
import org.apache.poi.xslf.usermodel.*
import java.io.{File, FileInputStream, FileOutputStream}

import scala.jdk.CollectionConverters.*
import scala.util.Using

/** Handles reading text from and writing translated text back to pptx files. */
object PptxService:

  private val TextDelimiter = "\n---\n"

  /** Extract all text elements from a pptx file.
    *
    * Walks every slide, shape, paragraph and text run, returning them in
    * document order along with their location indices so they can be
    * reassembled later.
    */
  def extractTexts(file: File): Seq[TextElement] =
    Using.resource(new XMLSlideShow(FileInputStream(file))) { ppt =>
      for
        (slide, si) <- ppt.getSlides.asScala.to(LazyList).zipWithIndex
        (shape, shi) <- slide.getShapes.asScala.to(LazyList).zipWithIndex
        elem <- extractFromShape(slide, si, shape, shi)
      yield elem
    }

  private def extractFromShape(
      slide: XSLFSlide,
      slideIndex: Int,
      shape: XSLFShape,
      shapeIndex: Int
  ): Seq[TextElement] =
    shape match
      case ts: XSLFTextShape => extractFromTextShape(ts, slideIndex, shapeIndex)
      case tbl: XSLFTable    => extractFromTable(tbl, slideIndex, shapeIndex)
      case grp: XSLFGroupShape =>
        grp.getShapes.asScala.toSeq.zipWithIndex.flatMap { (child, ci) =>
          extractFromShape(slide, slideIndex, child, ci)
        }
      case _ => Seq.empty // images, charts, connectors — skip

  private def extractFromTextShape(
      ts: XSLFTextShape,
      slideIndex: Int,
      shapeIndex: Int
  ): Seq[TextElement] =
    for
      (para, pi) <- ts.getTextParagraphs.asScala.toSeq.zipWithIndex
      (run, ri)  <- para.getTextRuns.asScala.toSeq.zipWithIndex
      text = run.getRawText
      if text != null && text.trim.nonEmpty
    yield TextElement(
      slideIndex     = slideIndex,
      shapeIndex     = shapeIndex,
      tableRowIndex  = None,
      paragraphIndex = pi,
      runIndex       = ri,
      originalText   = text
    )

  private def extractFromTable(
      tbl: XSLFTable,
      slideIndex: Int,
      shapeIndex: Int
  ): Seq[TextElement] =
    for
      (row, ri)  <- tbl.getRows.asScala.toSeq.zipWithIndex
      (cell, _)  <- row.getCells.asScala.toSeq.zipWithIndex
      (para, pi) <- cell.getTextParagraphs.asScala.toSeq.zipWithIndex
      (run, rui) <- para.getTextRuns.asScala.toSeq.zipWithIndex
      text = run.getRawText
      if text != null && text.trim.nonEmpty
    yield TextElement(
      slideIndex     = slideIndex,
      shapeIndex     = shapeIndex,
      tableRowIndex  = Some(ri),
      paragraphIndex = pi,
      runIndex       = rui,
      originalText   = text
    )

  /** Group texts into batches for translation, separated by a delimiter.
    * Returns the original elements alongside their batch index and position
    * within the batch.
    */
  def prepareBatches(
      elements: Seq[TextElement],
      maxBatchSize: Int
  ): Seq[Batch] =
    elements.grouped(maxBatchSize).toSeq.zipWithIndex.map { (group, idx) =>
      val text = group.map(_.originalText).mkString(TextDelimiter)
      Batch(idx, text, group.toList)
    }

  /** Write translated texts back into a copy of the original pptx. */
  def writeTranslated(
      source: File,
      elements: Seq[TextElement],
      outputFile: File
  ): Unit =
    // Build a lookup: (slide, shape, tableRow, paragraph, run) -> translated text
    val lookup: Map[(Int, Int, Option[Int], Int, Int), String] =
      elements.map(e => (e.slideIndex, e.shapeIndex, e.tableRowIndex, e.paragraphIndex, e.runIndex) -> e.translatedText).toMap

    Using.resources(
      new XMLSlideShow(FileInputStream(source)),
      new FileOutputStream(outputFile)
    ) { (ppt, out) =>
      for
        (slide, si) <- ppt.getSlides.asScala.toSeq.zipWithIndex
        (shape, shi) <- slide.getShapes.asScala.toSeq.zipWithIndex
      do
        shape match
          case ts: XSLFTextShape => writeTextShape(ts, si, shi, None, lookup)
          case tbl: XSLFTable    => writeTable(tbl, si, shi, lookup)
          case grp: XSLFGroupShape =>
            grp.getShapes.asScala.toSeq.zipWithIndex.foreach { (child, ci) =>
              child match
                case ts: XSLFTextShape => writeTextShape(ts, si, shi, None, lookup)
                case tbl: XSLFTable    => writeTable(tbl, si, shi, lookup)
                case _                => ()
            }
          case _ => ()

      ppt.write(out)
    }

  private def writeTextShape(
      ts: XSLFTextShape,
      slideIndex: Int,
      shapeIndex: Int,
      tableRow: Option[Int],
      lookup: Map[(Int, Int, Option[Int], Int, Int), String]
  ): Unit =
    for
      (para, pi) <- ts.getTextParagraphs.asScala.toSeq.zipWithIndex
      (run, ri)  <- para.getTextRuns.asScala.toSeq.zipWithIndex
    do
      val key = (slideIndex, shapeIndex, tableRow, pi, ri)
      lookup.get(key).foreach(run.setText)

  private def writeTable(
      tbl: XSLFTable,
      slideIndex: Int,
      shapeIndex: Int,
      lookup: Map[(Int, Int, Option[Int], Int, Int), String]
  ): Unit =
    for
      (row, ri)  <- tbl.getRows.asScala.toSeq.zipWithIndex
      (cell, _)  <- row.getCells.asScala.toSeq.zipWithIndex
      (para, pi) <- cell.getTextParagraphs.asScala.toSeq.zipWithIndex
      (run, rui) <- para.getTextRuns.asScala.toSeq.zipWithIndex
    do
      val key = (slideIndex, shapeIndex, Some(ri), pi, rui)
      lookup.get(key).foreach(run.setText)

// ---------------------------------------------------------------------------
// Helper types
// ---------------------------------------------------------------------------

/** A batch of text elements to translate together in one API call. */
case class Batch(index: Int, combinedText: String, elements: List[TextElement])
