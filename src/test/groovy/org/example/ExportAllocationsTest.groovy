package org.example

import org.junit.jupiter.api.Test
import java.nio.file.Files
import java.nio.file.Path
import java.text.SimpleDateFormat


import static org.junit.jupiter.api.Assertions.*

class ExportAllocationsTest {

    @Test
    void testWriteToCSV() {
        def data = [
                ["Project1", "Resource1", "2025-01-01", "2025-01-05", 5, 3],
                ["Project2", "Resource2", "2025-02-01", "2025-02-10", 4, 2]
        ]
        def outputFile = "test_output.csv"

        writeToCSV(data, outputFile)

        Path path = Path.of(outputFile)
        assertTrue(Files.exists(path), "The CSV file should exist")

        def fileContent = Files.readAllLines(path)
        assertEquals("Project,Resource,Segment Start,Segment End,Hard Allocation (PD),Soft Allocation (PD)", fileContent[0], "The first line should be the header")
        assertEquals("Project1,Resource1,2025-01-01,2025-01-05,5,3", fileContent[1], "The first data row should match")
        assertEquals("Project2,Resource2,2025-02-01,2025-02-10,4,2", fileContent[2], "The second data row should match")
        Files.delete(path)
    }

    @Test
    void testWriteToCSVEmptyData() {
        def data = []
        def outputFile = "empty_data_output.csv"

        writeToCSV(data, outputFile)

        Path path = Path.of(outputFile)
        assertTrue(Files.exists(path), "The CSV file should exist")

        def fileContent = Files.readAllLines(path)
        assertEquals("Project,Resource,Segment Start,Segment End,Hard Allocation (PD),Soft Allocation (PD)", fileContent[0], "The first line should be the header")
        assertEquals(1, fileContent.size(), "There should be no data rows for empty data")
        Files.delete(path)
    }

    def writeToCSV(data, outputFile) {
        println "Writing data to CSV file: $outputFile"
        try {
            File file = new File(outputFile)
            file.withWriter { writer ->
                writer.writeLine("Project,Resource,Segment Start,Segment End,Hard Allocation (PD),Soft Allocation (PD)")
                data.each { row ->
                    writer.writeLine(row.join(","))
                }
            }
            println "CSV file has been written successfully."
        } catch (IOException e) {
            println "Error writing to CSV: $e.message"
            throw e
        }
    }

    private Date createDate(String dateString) {
        def format = new SimpleDateFormat("yyyy-MM-dd")
        return format.parse(dateString)
    }

    @Test
    void testGetNumberOfPeriodsMonthly() {
        Date startDate = createDate("2023-01-01")
        Date endDate = createDate("2024-01-01")
        int result = getNumberOfPeriods(startDate, endDate, "MONTHLY")
        assertEquals(12, result, "The number of monthly periods should be 12")
    }

    @Test
    void testGetNumberOfPeriodsQuarterly() {
        Date startDate = createDate("2023-01-01")
        Date endDate = createDate("2024-01-01")
        int result = getNumberOfPeriods(startDate, endDate, "QUARTERLY")
        assertEquals(4, result, "The number of quarterly periods should be 4")
    }

    @Test
    void testGetNumberOfPeriodsYearly() {
        Date startDate = createDate("2023-01-01")
        Date endDate = createDate("2024-01-01")
        int result = getNumberOfPeriods(startDate, endDate, "YEARLY")
        assertEquals(1, result, "The number of yearly periods should be 1")
    }

    @Test
    void testGetNumberOfPeriodsMultipleYears() {
        Date startDate = createDate("2021-01-01")
        Date endDate = createDate("2024-01-01")
        int result = getNumberOfPeriods(startDate, endDate, "YEARLY")
        assertEquals(3, result, "The number of yearly periods should be 3")
    }

    @Test
    void testGetNumberOfPeriodsExactQuarterly() {
        Date startDate = createDate("2023-01-01")
        Date endDate = createDate("2023-04-01")
        int result = getNumberOfPeriods(startDate, endDate, "QUARTERLY")
        assertEquals(1, result, "The number of quarterly periods should be 1")
    }

    @Test
    void testGetNumberOfPeriodsInvalidPeriod() {
        Date startDate = createDate("2023-01-01")
        Date endDate = createDate("2024-01-01")
        Exception exception = assertThrows(Exception.class, () -> {
            getNumberOfPeriods(startDate, endDate, "INVALID")
        })

        assertEquals("Invalid period type: INVALID", exception.getMessage(), "Should throw exception for invalid period type")
    }

    @Test
    void testGetNumberOfPeriodsInvalidDateRange() {
        Date startDate = createDate("2024-01-01")
        Date endDate = createDate("2023-01-01")
        Exception exception = assertThrows(Exception.class, () -> {
            getNumberOfPeriods(startDate, endDate, "MONTHLY")
        })

        assertEquals("Invalid date range: Start date is after end date", exception.getMessage(), "Should throw exception when the start date is after the end date")
    }

    @Test
    void testWriteToCSVFileWritePermissionError() {
        def data = [
                ["Project1", "Resource1", "2025-01-01", "2025-01-05", 5, 3],
                ["Project2", "Resource2", "2025-02-01", "2025-02-10", 4, 2]
        ]
        def outputFile = "/protected_folder/test_output.csv" // assuming protected folder

        IOException exception = assertThrows(IOException.class, () -> {
            writeToCSV(data, outputFile)
        })

        // Check if an IOException is thrown
        assertNotNull(exception, "An IOException should be thrown when write permission is denied")

        // You can also log or assert more detailed information if required
        System.out.println("Exception message: " + exception.getMessage())
    }


    @Test
    void testGetNumberOfPeriodsNullStartDate() {
        Date startDate = null
        Date endDate = createDate("2024-01-01")
        Exception exception = assertThrows(Exception.class, () -> {
            getNumberOfPeriods(startDate, endDate, "MONTHLY")
        })

        assertEquals("Start date cannot be null", exception.getMessage(), "Should throw exception for null start date")
    }

    Integer getNumberOfPeriods(Date startDate, Date endDate, String period) {
        println "Calculating periods between $startDate and $endDate for period type: $period"
        if (startDate == null) {
            throw new Exception("Start date cannot be null")
        }
        if (startDate.after(endDate)) {
            throw new Exception("Invalid date range: Start date is after end date")
        }
        Calendar startCal = Calendar.getInstance()
        Calendar endCal = Calendar.getInstance()
        startCal.setTime(startDate)
        endCal.setTime(endDate)

        int yearsDiff = endCal.get(Calendar.YEAR) - startCal.get(Calendar.YEAR)
        int monthsDiff = endCal.get(Calendar.MONTH) - startCal.get(Calendar.MONTH)

        if (monthsDiff < 0) {
            yearsDiff--
            monthsDiff += 12
        }

        int totalMonthsDiff = (yearsDiff * 12) + monthsDiff
        int periods = 0

        switch (period) {
            case "MONTHLY":
                periods = totalMonthsDiff
                break
            case "QUARTERLY":
                periods = Math.ceil(totalMonthsDiff / 3.0) as Integer
                break
            case "YEARLY":
                periods = yearsDiff + (monthsDiff > 0 ? 1 : 0)
                break
            default:
                throw new Exception("Invalid period type: $period")
        }

        println "Total periods: $periods"
        return periods
    }
}
