package org.example

import com.niku.xmlserver.blob.NkCalendar
import com.niku.xmlserver.blob.NkCurve
import com.niku.xmlserver.blob.NkSegment
import com.niku.xmlserver.core.NkDate
import com.niku.xmlserver.core.NkTime
import de.itdesign.clarity.logging.CommonLogger
import groovy.sql.GroovyRowResult
import groovy.sql.Sql
import groovy.time.TimeCategory
import groovy.transform.Field
import java.sql.Blob
import java.sql.Connection
import java.sql.DriverManager
import java.text.SimpleDateFormat

// Database connection details
def url = "jdbc:oracle:thin:@//10.0.0.98:11521/clarity" // Replace with your DB details
def user = "niku"
def password = "niku"

// Establish the database connection
Connection connection = DriverManager.getConnection(url, user, password)
sql = new Sql(connection)

@Field CommonLogger cmnLog = new CommonLogger(this)
cmnLog.setFailJobOnError(true)

PROJECT = "5001082"
RESOURCE = "5004048"
//FROM_DATE = "2024-12-01"
//TO_DATE = "2025-12-01"

// Set FROM_DATE and TO_DATE to the specified range
def fromDateString = "2024-01-01 00:00:00"  // January 1st, 2024
def toDateString = "2024-12-31 00:00:00"  // December 31st, 2024

// Convert the string dates to Date objects
fromDate = Date.parse("yyyy-MM-dd HH:mm:ss", fromDateString)
toDate = Date.parse("yyyy-MM-dd HH:mm:ss", toDateString)
PERIOD = "MONTHLY" // Set period to "WEEKLY"


GroovyRowResult getProject(String projectId) {
    def query = """
                SELECT 
                      src.id 
                    , src.code
                    , src.name
                    , src.schedule_start
                    , src.schedule_finish 
                FROM 
                    inv_investments src 
                    where src.id = ? 
                """
    def result = sql.firstRow(query, [projectId])
    return result
}

GroovyRowResult getResource(String projectId, String resourceId) {
    def query = """
               SELECT 
            team.PRUID,
            team.prid,
            team.PRRESOURCEID,
            team.PRALLOCCURVE,
            team.HARD_CURVE,
            team.PRAVAILSTART,
            team.PRAVAILFINISH
        FROM 
            prteam team
        INNER JOIN inv_investments ii ON ii.id = team.prprojectid
        WHERE ii.id = ? AND team.PRRESOURCEID = ? 
                """
    def result = sql.firstRow(query, [projectId, resourceId])
    return result
}

NkCurve getCurveFromBlob(GroovyRowResult eachResource, String curveName) {
    println "Extracting Blob to NkCurve: $curveName"
    Blob curveBlob = eachResource?.get(curveName) as Blob

    if (!curveBlob) {
        println "Error: Curve Blob for $curveName is null."
        return null
    }
    byte[] curveBytes = curveBlob ? curveBlob.getBytes(1, (int) curveBlob.length()) : null
    NkCurve curve = curveBytes ? new NkCurve(curveBytes) : null
    if (!curve) {
        println "Error: Failed to create NkCurve from Blob for $curveName."
    }
    return curve
}

NkCurve getFilterSegments(NkCurve curve, Date start, Integer periods, String periodType) {
    println "Filtering curve for periods starting from $start with $periods $periodType periods."
    if (!curve) {
        println "Error: curve is null, cannot filter."
        return null
    }
    NkCurve filteredCurve = new NkCurve(1)

    use(TimeCategory) {
        for (int i = 0; i < periods; i++) {
            def periodStartDate
            def periodEndDate
            switch (periodType) {
                case "MONTHLY":
                    periodStartDate = start + i.month
                    periodEndDate = start + (i + 1).month
                    break
                case "QUARTERLY":
                    periodStartDate = start + (i * 3).months
                    periodEndDate = start + ((i + 1) * 3).months
                    break
                case "YEARLY":
                    periodStartDate = start + (i * 12).months
                    periodEndDate = start + ((i + 1) * 12).months
                    break
                case "WEEKLY":
                    periodStartDate = start + i.week
                    periodEndDate = start + (i + 1).week - 1.day
                    break
                default:
                    throw new Exception("Invalid period type: $periodType")
            }
            curve.segments.each { NkSegment segment ->
                if (segment.startDate >= periodStartDate && segment.finishDate <= periodEndDate) {
                    filteredCurve.segments.setSegment(segment)
                }
            }
            println " the size of the filter curve is ${filteredCurve.segments.size()}"
            if (filteredCurve.segments.size() == 0) {
                filteredCurve.segments.setSegment(NkTime.toNkTime(periodStartDate), NkTime.toNkTime(periodEndDate), 0.0D, null)
                println "No segments found for this period. Added default segment with 0 allocation."
            }
        }
    }
    return filteredCurve
}

def writeToCSV(data, outputFile) {
    println "Writing data to CSV file: $outputFile"
    File file = new File(outputFile)
    file.withWriter { writer ->
        writer.writeLine("Project,Resource,Segment Start,Segment End,Hard Allocation (PD),Soft Allocation (PD)")
        data.each { row ->
            println "Writing Row: $row"
            writer.writeLine(row.join(","))
        }
    }
    println "CSV file has been written successfully."
}

Integer getNumberOfPeriods(Date startDate, Date endDate, String period) {
    println "Calculating periods between $startDate and $endDate for period type: $period"
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
            // For YEARLY periods, calculate the number of complete years and adjust for partial years if necessary
            periods = yearsDiff + (monthsDiff > 0 ? 1 : 0) // 1 year if there is any extra month
            break
        case "WEEKLY":
            // For WEEKLY periods, calculate the difference in weeks
            long diffInMillis = endDate.time - startDate.time
            long diffInWeeks = diffInMillis / (1000 * 60 * 60 * 24 * 7) // Convert milliseconds to weeks
            periods = diffInWeeks.toInteger() // Rounds to the nearest whole number of weeks
            break
        default:
            throw new Exception("Invalid period type: $period")
    }

    println "Total periods: $periods"
    return periods
}


def getPersonDays(NkCurve curve, Date fromDate, Date toDate) {
    def days = 0
    NkCalendar calendar = new NkCalendar()

    switch (PERIOD) {
        case "MONTHLY":
            def nkPeriodStartDate = NkTime.toNkTime(fromDate)
            def Date = nkPeriodStartDate.add(2 * 24 * 60 * 60)
            def rate = curve.segments.getRate(Date)
            def startDate = new NkDate(fromDate, false)
            def endDate = new NkDate(toDate, false)
            def diffWorkingDays = calendar.diffWorkday(startDate, endDate)
            days = (rate * diffWorkingDays).round(2)
            break

        case "QUARTERLY":
            def nkPeriodStartDate = NkTime.toNkTime(fromDate)
            def Date = nkPeriodStartDate.add(2 * 24 * 60 * 60)
            def rate = curve.segments.getRate(Date)
            def startDate = new NkDate(fromDate, false)
            def endDate = new NkDate(toDate, false)
            def diffWorkingDays = calendar.diffWorkday(startDate, endDate)
            days = (rate * diffWorkingDays).round(2)
            break

        case "YEARLY":
            // For YEARLY, calculate the sum of the rates over the entire year and take the average
            def startOfYear = fromDate.clearTime()  // Ensure it is the first day of the year
            def endOfYear = toDate.clearTime()  // Ensure it is the last day of the year
            def totalRate = 0.0
            def count = 0

            // Loop through the months or periods in the year to sum the rates
            use(TimeCategory) {
                def currentDate = startOfYear
                while (currentDate <= endOfYear) {
                    def nkPeriodStartDate = NkTime.toNkTime(currentDate)
                    def rate = curve.segments.getRate(nkPeriodStartDate)
                    totalRate += rate
                    count++
                    currentDate = currentDate + 1.month
                }
            }

            def averageRate = totalRate / count
            def startDate = new NkDate(fromDate, false)
            def endDate = new NkDate(toDate, false)
            def diffWorkingDays = calendar.diffWorkday(startDate, endDate)
            days = (averageRate * diffWorkingDays).round(2)
            break

        case "WEEKLY":
            def nkPeriodStartDate = NkTime.toNkTime(fromDate)
            def Date = nkPeriodStartDate.add(2 * 24 * 60 * 60)
            def rate = curve.segments.getRate(Date)
            def startDate = new NkDate(fromDate, false)
            def endDate = new NkDate(toDate, false)
            def diffWorkingDays = calendar.diffWorkday(startDate, endDate)
            days = (rate * diffWorkingDays).round(2)
            break

        default:
            throw new IllegalArgumentException("Unsupported period: $PERIOD")
    }
    return days
}

def createCsv(NkCurve curve, GroovyRowResult investment, GroovyRowResult teamData, NkCurve softCurve, NkCurve hardCurve) {
    List<List<String>> csvData = []
    NkCalendar calendar = new NkCalendar()
    use(TimeCategory) {
        def currentStartDate = fromDate
        while (currentStartDate <= toDate) {
            def currentEndDate

            switch (PERIOD) {
                case "MONTHLY":
                    currentEndDate = currentStartDate + 1.month - 1.day
                    break
                case "QUARTERLY":
                    currentEndDate = currentStartDate + 3.month - 1.day
                    break
                case "YEARLY":
                    currentEndDate = currentStartDate + 1.year - 1.day
                    break
                case "WEEKLY":
                    currentEndDate = currentStartDate + 1.week - 1.day
                    break
                default:
                    throw new IllegalArgumentException("Unsupported period: $PERIOD")
            }

            if (currentEndDate > toDate) {
                currentEndDate = toDate
            }
            def days = getPersonDays(curve, currentStartDate, currentEndDate)
            def formatDate = new SimpleDateFormat("dd.MMM.yyyy")
            def formatStartDate = formatDate.format(currentStartDate)
            def formatEndDate = formatDate.format(currentEndDate)

            def row = [
                    investment.name,
                    teamData.PRRESOURCEID,
                    formatStartDate,
                    formatEndDate,
                    softCurve ? days : 0,
                    hardCurve ? days : 0
            ]
            csvData << row
            switch (PERIOD) {
                case "MONTHLY":
                    currentStartDate = currentStartDate + 1.month
                    break
                case "QUARTERLY":
                    currentStartDate = currentStartDate + 3.month
                    break
                case "YEARLY":
                    currentStartDate = currentStartDate + 1.year
                    break
                case "WEEKLY":
                    currentStartDate = currentStartDate + 1.week
                    break
            }
        }
    }
    writeToCSV(csvData, "allocation_output.csv")
    return csvData
}

def generateCsvForAllocations(String projectId, String resourceId, Date fromDate, Date toDate, String period) {
    GroovyRowResult investment = getProject(projectId)
    Integer periods = getNumberOfPeriods(fromDate, toDate, period)
    GroovyRowResult teamData = getResource(investment.id as String, resourceId)
    NkCurve softCurve = getCurveFromBlob(teamData, "PRALLOCCURVE")
    NkCurve hardCurve = getCurveFromBlob(teamData, "HARD_CURVE")
    if (softCurve) {
        filterSoftCurve = getFilterSegments(softCurve, fromDate, periods, period)
        createCsv(filterSoftCurve, investment, teamData, softCurve, hardCurve)
    }
    if (hardCurve) {
        filterHardCurve = getFilterSegments(hardCurve, fromDate, periods, period)
        createCsv(filterHardCurve, investment, teamData, softCurve, hardCurve)
    }
}

void assertParameters() {
    generateCsvForAllocations(PROJECT as String, RESOURCE as String, fromDate, toDate, PERIOD as String)
}

def runScript() {
    cmnLog.info "Started exporting allocations at:-${new Date()}"
    assertParameters()
    cmnLog.info "Finished exporting allocations at:${new Date()}"
}

runScript()
