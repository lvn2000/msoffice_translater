package service

import model.TextElement
import java.io.File

/** Type class for document formats that can be translated.
  *
  * Each format (pptx, docx, xls, pdf) provides a given instance
  * that defines which file extensions it handles and how to
  * extract / write text.
  */
trait DocumentFormat:

  /** File extensions this format handles (e.g. "pptx", "docx", "xls"). */
  def extensions: Seq[String]

  /** Extract all text elements from a file.
    *
    * @return Right(elements) on success, Left(error) on failure
    */
  def extractTexts(file: File): Either[String, Seq[TextElement]]

  /** Write translated texts into a copy of the source file.
    *
    * @param source     the original file
    * @param elements   translated text elements
    * @param outputFile the output file to create
    * @param langCode   language code (e.g. "ru", "en") — may be used to
    *                   set document language metadata where supported
    * @return Right(()) on success, Left(error) on failure
    */
  def writeTranslated(
      source: File,
      elements: Seq[TextElement],
      outputFile: File,
      langCode: String
  ): Either[String, Unit]

  /** Set document language metadata on the output file.
    *
    * Override in format-specific implementations that support it
    * (e.g. Office Open XML formats).  Default is a no-op.
    */
  def setDocumentLanguage(outputFile: File, langCode: String): Either[String, Unit] =
    Right(())
