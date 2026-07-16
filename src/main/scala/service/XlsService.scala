package service

import model.TextElement
import org.apache.poi.ss.usermodel.{CellType, WorkbookFactory}

import java.io.{File, FileOutputStream}
import scala.jdk.CollectionConverters.*
import scala.util.{Try, Using}

/** Handles reading text from and writing translated text back to xls/xlsx files. */
object XlsService extends DocumentFormat:

  override val extensions: Seq[String] = Seq("xls", "xlsx")

  override def extractTexts(file: File): Either[String, Seq[TextElement]] =
    Try {
      Using.resource(WorkbookFactory.create(file)) { wb =>
        for
          si <- (0 until wb.getNumberOfSheets).to(LazyList)
          sheet = wb.getSheetAt(si)
          (row, ri) <- sheet.rowIterator().asScala.to(LazyList).zipWithIndex
          (cell, ci) <- row.cellIterator().asScala.to(LazyList).zipWithIndex
          if cell.getCellType == CellType.STRING
          text = cell.getStringCellValue
          if text != null && text.trim.nonEmpty
        yield TextElement(
          slideIndex     = si,
          shapeIndex     = ri,
          tableRowIndex  = None,
          paragraphIndex = ci,
          runIndex       = 0,
          originalText   = text
        )
      }
    }.toEither.left.map(e => s"Failed to read xls/xlsx: ${e.getMessage}")

  override def writeTranslated(
      source: File,
      elements: Seq[TextElement],
      outputFile: File
  ): Either[String, Unit] =
    val lookup = DocumentService.buildLookup(elements)
    Try {
      Using.resources(
        WorkbookFactory.create(source),
        new FileOutputStream(outputFile)
      ) { (wb, out) =>
        for
          si <- 0 until wb.getNumberOfSheets
          sheet = wb.getSheetAt(si)
          (row, ri) <- sheet.rowIterator().asScala.toSeq.zipWithIndex
          (cell, ci) <- row.cellIterator().asScala.toSeq.zipWithIndex
          if cell.getCellType == CellType.STRING
        do
          val key = (si, ri, None, ci, 0)
          lookup.get(key).foreach(cell.setCellValue)

        wb.write(out)
      }
    }.toEither.left.map(e => s"Failed to write xls/xlsx: ${e.getMessage}")

end XlsService
