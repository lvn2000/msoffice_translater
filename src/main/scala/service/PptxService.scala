package service

import model.TextElement
import org.apache.poi.xslf.usermodel.*
import java.io.{File, FileInputStream, FileOutputStream}

import scala.jdk.CollectionConverters.*
import scala.util.{Try, Using}

/** Handles reading text from and writing translated text back to pptx files. */
object PptxService extends DocumentFormat:

  override val extensions: Seq[String] = Seq("pptx")

  override def extractTexts(file: File): Either[String, Seq[TextElement]] =
    Try {
      Using.resource(new XMLSlideShow(FileInputStream(file))) { ppt =>
        for
          (slide, si) <- ppt.getSlides.asScala.to(LazyList).zipWithIndex
          (shape, shi) <- slide.getShapes.asScala.to(LazyList).zipWithIndex
          elem <- extractFromShape(slide, si, shape, shi)
        yield elem
      }
    }.toEither.left.map(e => s"Failed to read pptx: ${e.getMessage}")

  override def writeTranslated(
      source: File,
      elements: Seq[TextElement],
      outputFile: File,
      langCode: String
  ): Either[String, Unit] =
    val lookup = DocumentService.buildLookup(elements)
    Try {
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
    }.toEither.left.map(e => s"Failed to write pptx: ${e.getMessage}")

  // --------------------------------------------------------------------------
  // Private helpers
  // --------------------------------------------------------------------------

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
      case _ => Seq.empty

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

end PptxService
