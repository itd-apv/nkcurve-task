package org.example

import com.niku.xmlserver.blob.NkCurve
import com.niku.xmlserver.blob.NkSegment
import de.itdesign.clarity.logging.CommonLogger
import groovy.sql.Sql
import groovy.time.TimeCategory
import groovy.transform.Field

import java.sql.DriverManager
import java.sql.Connection
import java.sql.Blob


@Field CommonLogger cmnLog = new CommonLogger(this)

def url = "jdbc:oracle:thin:@//10.0.0.98:11521/clarity" // Replace with your DB details
def user = "niku"
def password = "niku"

Connection connection = DriverManager.getConnection(url, user, password)
def sql = new Sql(connection)

project = 5001123
resource = 5004037
period = "monthly"
fromDate = Date.parse("yyyy-MM-dd", "2024-11-01")
toDate = Date.parse("yyyy-MM-dd", "2025-01-01")


// Method to fetch the allocation curve (soft or hard) for the resource in the given project
NkCurve getAllocationCurve(Sql sql, int projectId, int resourceId, String allocationType) {
    def query = """
        SELECT pt.${allocationType}
        FROM prteam pt
        JOIN inv_investments ii ON pt.prprojectid = ii.id
        JOIN srm_resources sr ON pt.prresourceid = sr.id
        WHERE ii.id = ?
        AND sr.id = ?
    """
    def result = sql.firstRow(query, [projectId, resourceId])

    println "Result for ${allocationType}: ${result}"

    if (result && result["${allocationType}"] instanceof Blob) {
        println "Allocation curve for ${allocationType} fetched successfully."

        // Extract bytes from Blob (instead of oracle.sql.BLOB)
        Blob blob = result["${allocationType}"]
        println "${extractBlobData(blob)}"
        return extractBlobData(blob)
    } else {
        println "No allocation curve found for ${allocationType}."
        return null
    }
}

// Helper method to extract byte data from the Blob
NkCurve extractBlobData(Blob blob) {
    try {
        InputStream inputStream = blob.getBinaryStream()
        byte[] byteArray = inputStream.bytes  // Converts InputStream to byte[]
        inputStream.close()
        return new NkCurve(byteArray)  // Return NkCurve created from byteArray
    } catch (Exception e) {
        println "Error extracting Blob data: ${e.message}"
        return null  // Return null in case of error
    }
}

// Method to write the allocation data to a CSV file
void writeToCSV(List<Map<String, Object>> data, String filename) {
    File file = new File(filename)
    BufferedWriter writer = new BufferedWriter(new FileWriter(file))
    writer.write("Project ID,Project Name,Resource,Segment Start,Segment End,Hard Allocation Working Days,Soft Allocation Working Days\n")

    data.each { entry ->
        writer.write("${entry.projectId},${entry.projectName},${entry.resource},${entry.segmentStart},${entry.segmentEnd},${entry.hardAllocationWorkingDays},${entry.softAllocationWorkingDays}\n")
    }

    writer.close()
}

// Method to process allocations and write to CSV
void processAllocationsWithWorkingDays(Sql sql, int projectId, int resourceId, Date fromDate, Date toDate, String period, String projectName) {
    List<Map<String, Object>> allocationData = []

    println "Processing allocations for resource: ${resourceId}"

    NkCurve softCurve = getAllocationCurve(sql, projectId, resourceId, "pralloccurve")
    NkCurve hardCurve = getAllocationCurve(sql, projectId, resourceId, "hard_curve")

    // Handle missing soft curve
    if (softCurve == null) {
        println "No soft allocation curve found for resource ${resourceId}, terminating job for this resource."
        return
    }

    // Set hardCurve to null if not found
    if (hardCurve == null) {
        println "Hard allocation curve not found for resource ${resourceId}, setting hard allocation curve to null."
        hardCurve = null
    }

    // Iterate through each period
    use(TimeCategory) {
        Date currentDate = fromDate
        while (currentDate <= toDate) {
            Date nextDate = adjustDateForPeriod(currentDate, period)

            // Get the working days for the soft and hard allocation curves for the current period
            def softWorkingDays = getWorkingDaysForCurvePeriod(softCurve, currentDate, nextDate)
            def hardWorkingDays = (hardCurve != null) ? getWorkingDaysForCurvePeriod(hardCurve, currentDate, nextDate) : 0.0

            // Add the data to the list for output
            allocationData.add([
                    projectId: projectId,
                    projectName: projectName,
                    resource: resourceId,
                    segmentStart: currentDate.format('dd.MMM.yyyy'),
                    segmentEnd: nextDate.format('dd.MMM.yyyy'),
                    hardAllocationWorkingDays: hardWorkingDays,
                    softAllocationWorkingDays: softWorkingDays
            ])

            // Move to the next period
            currentDate = nextDate
        }
    }

    // Write the results to a CSV file
    writeToCSV(allocationData, "resource_allocations_with_working_days_${projectId}_${fromDate.format('yyyyMMdd')}_${toDate.format('yyyyMMdd')}.csv")
}

// Helper method to get the next period end date based on the period type (daily, weekly, monthly)
Date adjustDateForPeriod(Date currentDate, String period) {
    Calendar calendar = Calendar.getInstance()
    calendar.setTime(currentDate)

    switch (period.toLowerCase()) {
        case "daily":
            calendar.add(Calendar.DAY_OF_YEAR, 1)
            break
        case "weekly":
            calendar.add(Calendar.WEEK_OF_YEAR, 1)
            break
        case "monthly":
            calendar.add(Calendar.MONTH, 1)
            break
        case "quarterly":
            calendar.add(Calendar.MONTH, 3)
            break
        default:
            throw new IllegalArgumentException("Unknown period type: ${period}")
    }

    return calendar.time
}
// Method to count working days (excluding weekends)
def countWorkingDays(Date start, Date end) {
    int workingDays = 0
    Calendar calendar = Calendar.getInstance()
    calendar.setTime(start)

    if (calendar.get(Calendar.DAY_OF_WEEK) == Calendar.SATURDAY) {
        calendar.add(Calendar.DAY_OF_MONTH, 2)
    } else if (calendar.get(Calendar.DAY_OF_WEEK) == Calendar.SUNDAY) {
        calendar.add(Calendar.DAY_OF_MONTH, 1)
    }

    while (!calendar.getTime().after(end)) {
        if (calendar.get(Calendar.DAY_OF_WEEK) >= Calendar.MONDAY && calendar.get(Calendar.DAY_OF_WEEK) <= Calendar.FRIDAY) {
            workingDays++
        }
        calendar.add(Calendar.DAY_OF_MONTH, 1)
    }
    return workingDays
}

// Helper method to get the working days for a given period (based on NkCurve segments)
def getWorkingDaysForCurvePeriod(NkCurve curve, Date startDate, Date endDate) {
    def totalWorkingDays = 0

    println "Calculating working days for the period: ${startDate.format('dd.MMM.yyyy')} to ${endDate.format('dd.MMM.yyyy')}"

    curve?.segments?.each { NkSegment segment ->
        // Print segment details
        println "Processing segment: Start Date = ${segment.startDate.format('dd.MMM.yyyy')}, End Date = ${segment.finishDate.format('dd.MMM.yyyy')}, Rate = ${segment.rate}"

        // Skip zero-rate segments
        if (segment.rate == 0.0) {
            println "Skipping segment with zero rate"
            return
        }

        // Determine the start and finish dates for this segment
        Date segmentStart = segment.startDate
        Date segmentEnd = segment.finishDate

        // Calculate overlap between the curve segment and the specified period (startDate to endDate)
        def overlapStart = startDate.after(segmentStart) ? startDate : segmentStart
        def overlapEnd = endDate.before(segmentEnd) ? endDate : segmentEnd

        // If the overlap period is valid, calculate the working days
        if (!overlapStart.after(overlapEnd)) {
            int workingDaysForPeriod = countWorkingDays(overlapStart, overlapEnd)
            totalWorkingDays += workingDaysForPeriod
            println "Working days for this segment: ${workingDaysForPeriod}"
        }
    }

    println "Total working days for the period: ${totalWorkingDays}"
    return totalWorkingDays
}

// Main method to process and calculate working days in each period
def projectDetails = sql.firstRow("""
    SELECT id, name
    FROM inv_investments
    WHERE id = ?
""", [project])

if (projectDetails) {
    String projectName = projectDetails.name
    // Run the allocation job, now including working days
    processAllocationsWithWorkingDays(sql, project, resource, fromDate, toDate, period, projectName)
} else {
    println "No project found with ID: ${project}"
}

void assertParameters() {
    if (!binding.variables.containsKey("z_from_date")) {
        def STRING_DATE = binding.variables.get('z_from_date')
        def formattedString = STRING_DATE.replace("T", " ") // Replace T with space
        def date = Date.parse("yyyy-MM-dd HH:mm:ss", formattedString)
        FROM_DATE = date
    }

    if (binding.variables.containsKey("z_to_date")) {
        def STRING_DATE = binding.variables.get('z_to_date')
        def formattedString = STRING_DATE.replace("T", " ") // Replace T with space
        def date = Date.parse("yyyy-MM-dd HH:mm:ss", formattedString)
        TO_DATE = date
    }

    if (toDate != null && fromDate.after(toDate)) {
        throw new Exception("The Date from when allocations to be read '${fromDate}' lies after the Date until when allocations to be read '${TO_DATE}'")
    }

    PROJECT = binding.variables.get('z_project')
    cmnLog.info "Project:-${project}"
    RESOURCE = binding.variables.get('z_resource')
    cmnLog.info "Resource:-${resource}"
    PERIOD = binding.variables.get('z_period')
    cmnLog.info "Period:-${period}"
}

def runScript() {
    cmnLog.info "Started exporting allocations at:-${new Date()}"
    assertParameters()
    cmnLog.info "Finished exporting allocations at:${new Date()}"
}

runScript()
