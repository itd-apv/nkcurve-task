import com.niku.xmlserver.blob.NkCurve
import com.niku.xmlserver.blob.NkSegment
import groovy.sql.Sql
import groovy.time.TimeCategory
import java.sql.DriverManager
import java.sql.Connection
import java.sql.Blob

// Database connection details
def url = "jdbc:oracle:thin:@//10.0.0.98:11521/clarity" // Replace with your DB details
def user = "niku"
def password = "niku"

// Establish the database connection
Connection connection = DriverManager.getConnection(url, user, password)
def sql = new Sql(connection)

// Parameters for the AllocationMigrationJob
def period = "monthly" // Options: "daily", "weekly", "monthly", "quarterly"

// Method to fetch all projects from inv_investments table
List<Map<String, Object>> getAllProjects(Sql sql) {
    def query = """
        SELECT code, name, schedule_start, schedule_finish
        FROM inv_investments
    """
    def result = sql.rows(query)
    return result.collect { [projectCode: it.code, projectName: it.name, fromDate: it.schedule_start, toDate: it.schedule_finish] }
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

// Method to fetch the allocation curve (soft or hard) for the resource in the given project
NkCurve getAllocationCurve(Sql sql, String project, String resource, String allocationType) {
    def query = """
        SELECT pt.${allocationType}
        FROM prteam pt
        JOIN inv_investments ii ON pt.prprojectid = ii.id
        JOIN srm_resources sr ON pt.prresourceid = sr.id
        WHERE ii.code = ?  
        AND sr.unique_name = ?
    """
    def result = sql.firstRow(query, [project, resource])

    if (result && result["${allocationType}"] instanceof Blob) {
        Blob blob = result["${allocationType}"]
        return extractBlobData(blob)
    } else {
        return null
    }
}

// Method to get all resources for the given project
List<String> getResourcesForProject(Sql sql, String project) {
    def query = """
        SELECT sr.unique_name
        FROM prteam pt
        JOIN inv_investments ii ON pt.prprojectid = ii.id
        JOIN srm_resources sr ON pt.prresourceid = sr.id
        WHERE ii.code = ?
    """
    def result = sql.rows(query, [project])
    return result.collect { it.unique_name }
}

// Helper method to print the contents of the NkCurve
def printNkCurveContents(NkCurve nkCurve) {
    if (nkCurve != null && nkCurve.segments != null) {
        nkCurve.segments.eachWithIndex { NkSegment segment, int index ->
            println "Segment ${index + 1}:"
            println "  Start Date: ${segment.startDate}"
            println "  Finish Date: ${segment.finishDate}"
            println "  Allocation Rate: ${segment.rate}"
        }
    } else {
        println "NkCurve is empty or null."
    }
}

// Method to write the allocation data to a CSV file
void writeToCSV(List<Map<String, Object>> data, String filename) {
    File file = new File(filename)
    BufferedWriter writer = new BufferedWriter(new FileWriter(file))
    writer.write("Project Code,Project Name,Resource,Segment Start,Segment End,Hard Allocation (PD),Soft Allocation (PD)\n")

    data.each { entry ->
        writer.write("${entry.projectCode},${entry.projectName},${entry.resource},${entry.segmentStart},${entry.segmentEnd},${entry.hardAllocation},${entry.softAllocation}\n")
    }

    writer.close()
}

// Method to process allocations for all projects and resources and write to CSV
void processAllocationsForAllProjects(Sql sql, List<Map<String, Object>> allProjects, String period) {
    List<Map<String, Object>> allAllocationData = []

    allProjects.each { project ->
        def projectCode = project.projectCode
        def projectName = project.projectName
        def fromDate = project.fromDate
        def toDate = project.toDate

        // Get all resources for the current project
        List<String> resources = getResourcesForProject(sql, projectCode)

        resources.each { resource ->
            println "Processing allocations for resource: ${resource} in project: ${projectName}"

            NkCurve softCurve = getAllocationCurve(sql, projectCode, resource, "pralloccurve")
            NkCurve hardCurve = getAllocationCurve(sql, projectCode, resource, "hard_curve")

            // If no soft allocation curve, skip the resource
            if (softCurve == null) {
                println "No soft allocation curve found for resource ${resource}, skipping."
                return
            }

            // Handle hard allocation curve (if any)
            if (hardCurve == null) {
                println "Hard allocation curve not found for resource ${resource}, setting hard allocation curve to null."
                hardCurve = null
            }

            // Process each period (daily, weekly, monthly)
            use(TimeCategory) {
                Date currentDate = fromDate
                while (currentDate <= toDate) {
                    def nextDate = getNextPeriod(currentDate, period)

                    // Get Soft and Hard Allocations for the current period
                    def softAllocation = getAllocationForPeriod(softCurve, currentDate, nextDate)
                    def hardAllocation = hardCurve ? getAllocationForPeriod(hardCurve, currentDate, nextDate) : 0.0

                    // Add the current period's data to the list
                    allAllocationData.add([
                            projectCode: projectCode,
                            projectName: projectName,
                            resource: resource,
                            segmentStart: currentDate.format('dd.MMM.yyyy'),
                            segmentEnd: nextDate.format('dd.MMM.yyyy'),
                            hardAllocation: hardAllocation,
                            softAllocation: softAllocation
                    ])

                    // Move to the next period
                    currentDate = nextDate
                }
            }
        }
    }

    // Write the data to a CSV file
    writeToCSV(allAllocationData, "all_projects_resource_allocations.csv")
}

// Method to get the next period end date based on the period type (daily, weekly, monthly)
Date getNextPeriod(Date currentDate, String period) {
    switch (period.toLowerCase()) {
        case "daily":
            return currentDate + 1.day
        case "weekly":
            return currentDate + 1.week
        case "monthly":
            return currentDate + 1.month
        case "quarterly":
            return currentDate + 3.month
        default:
            throw new IllegalArgumentException("Unknown period type: ${period}")
    }
}

// Method to get the allocation value for the given period
Double getAllocationForPeriod(NkCurve curve, Date startDate, Date endDate) {
    Double totalAllocation = 0.0
    curve?.segments?.each { NkSegment segment ->
        boolean overlap = (segment.startDate >= startDate && segment.startDate <= endDate) ||
                (segment.finishDate >= startDate && segment.finishDate <= endDate) ||
                (segment.startDate <= startDate && segment.finishDate >= endDate)

        if (overlap) {
            totalAllocation += segment.rate
        }
    }

    return totalAllocation ?: 0.00  // Return 0.00 if no allocation is found or curve is null
}

// Fetch all projects from the database
def allProjects = getAllProjects(sql)

// Run the allocation migration job for all projects and resources
processAllocationsForAllProjects(sql, allProjects, period)

// Close the database connection after use
connection.close()
