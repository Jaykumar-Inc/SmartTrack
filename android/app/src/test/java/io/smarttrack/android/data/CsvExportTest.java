package io.smarttrack.android.data;

import static org.junit.Assert.*;

import org.junit.Test;

public class CsvExportTest {
  @Test
  public void preservesMissingValuesAndNumericSigns() {
    assertEquals("", CsvExport.cell(null));
    assertEquals("\"-1.25\"", CsvExport.cell(-1.25));
    assertEquals("\"0\"", CsvExport.cell(0));
  }

  @Test
  public void escapesQuotesAndSpreadsheetExpressions() {
    assertEquals("\"a,\"\"b\"\"\"", CsvExport.cell("a,\"b\""));
    assertEquals("\"'=1+1\"", CsvExport.cell("=1+1"));
    assertEquals("\"'-device\"", CsvExport.cell("-device"));
  }
}
