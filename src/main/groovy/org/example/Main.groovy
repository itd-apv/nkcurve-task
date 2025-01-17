import com.niku.xmlserver.blob.NkCurve
import com.niku.xmlserver.blob.NkSegment
import groovy.sql.Sql
import groovy.time.TimeCategory
import java.sql.DriverManager
import java.sql.Connection
import java.sql.Blob

class AllocationMigrationJob {

    Sql sql
    String project
    String resource
    Date fromDate
    Date toDate
    String period

    // Constructor to initialize parameters
    AllocationMigrationJob(Sql sql, String project, String resource, Date fromDate, Date toDate, String period) {
        this.sql = sql
        this.project = project
        this.resource = resource
        this.fromDate = fromDate
        this.toDate = toDate
        this.period = period
    }

    // Method to fetch the allocation curve (soft or hard) for the resource in the given project
    NkCurve getAllocationCurve(String allocationType) {
        def query = """
            SELECT ${allocationType}
            FROM prteam
            WHERE prprojectid = (SELECT id FROM inv_investments WHERE code = ?)
              AND prresourceid = (SELECT id FROM srm_resources WHERE unique_name = ?)
        """
        def result = sql.firstRow(query, [project, resource])

        if (result && result["${allocationType}"] instanceof Blob) {
            println "Allocation curve for ${allocationType} fetched successfully."

            // Extract bytes from Blob (instead of oracle.sql.BLOB)
            Blob blob = result["${allocationType}"]
            byte[] byteArray = extractBlobData(blob)

            return new NkCurve(byteArray)
        } else {
            println "No allocation curve found for ${allocationType}."
            return null
        }
    }

    // Helper method to extract byte data from the Blob
    byte[] extractBlobData(Blob blob) {
        try {
            InputStream inputStream = blob.getBinaryStream()
            byte[] byteArray = inputStream.bytes  // Converts InputStream to byte[]
            inputStream.close()
            return byteArray
        } catch (Exception e) {
            println "Error extracting Blob data: ${e.message}"
            return []  // Return an empty byte array in case of error
        }
    }

    // Method to write the allocation data to a CSV file
    void writeToCSV(List<Map<String, Object>> data, String filename) {
        File file = new File(filename)
        BufferedWriter writer = new BufferedWriter(new FileWriter(file))
        writer.write("Project,Resource,Segment Start,Segment End,Hard Allocation (PD),Soft Allocation (PD)\n")

        data.each { entry ->
            writer.write("${entry.project},${entry.resource},${entry.segmentStart},${entry.segmentEnd},${entry.hardAllocation},${entry.softAllocation}\n")
        }

        writer.close()
    }


    // Method to split the project duration into time segments (based on period) and fetch the allocation data
    void processAllocations() {
        NkCurve softCurve = getAllocationCurve("pralloccurve")
        NkCurve hardCurve = getAllocationCurve("ESTIMATES_CURVE")

        // Handle missing soft curve
        if (softCurve == null) {
            println "No soft allocation curve found, terminating job."
            return
        }

        // Handle missing hard curve
        if (hardCurve == null) {
            println "Hard allocation curve not found, using default allocation value of 0."
            // Initialize with an empty NkCurve or with a default value
            hardCurve = new NkCurve([])  // Initialize with an empty curve
        }

        List<Map<String, Object>> allocationData = []

        // Split the duration into segments based on the period (daily, weekly, monthly)
        use(TimeCategory) {
            Date currentDate = fromDate
            while (currentDate <= toDate) {
                def nextDate = getNextPeriod(currentDate)

                // Get the Soft and Hard Allocations for the current period
                def softAllocation = getAllocationForPeriod(softCurve, currentDate, nextDate)
                def hardAllocation = getAllocationForPeriod(hardCurve, currentDate, nextDate)

                // Add the current period's data to the list
                allocationData.add([
                        project: project,
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

        // Write the data to a CSV file
        writeToCSV(allocationData, "resource_allocations_${project}_${resource}_${fromDate.format('yyyyMMdd')}_${toDate.format('yyyyMMdd')}.csv")
    }


    // Method to get the next period end date based on the period type (daily, weekly, monthly)
    Date getNextPeriod(Date currentDate) {
        switch (period.toLowerCase()) {
            case "daily":
                return currentDate + 1.day
            case "weekly":
                return currentDate + 1.week
            case "monthly":
                return currentDate + 1.month
            default:
                throw new IllegalArgumentException("Unknown period type: ${period}")
        }
    }

    // Method to get the allocation value for the given period
    Double getAllocationForPeriod(NkCurve curve, Date startDate, Date endDate) {
        Double totalAllocation = 0.0
        curve.segments.each { NkSegment segment ->
            if (segment.startDate >= startDate && segment.finishDate < endDate) {
                totalAllocation += segment.rate
            }
        }
        return totalAllocation
    }
}

// Example Usage: Setting up database connection
def url = "jdbc:oracle:thin:@//10.0.0.98:11521/clarity" // Replace with your DB details
def user = "niku"
def password = "niku"

// Establish the database connection
Connection connection = DriverManager.getConnection(url, user, password)

// Create a Sql object using the connection
def sql = new Sql(connection)

// Parameters for the AllocationMigrationJob
def project = "PR1016"
def resource = "jasonBerry"
def fromDate = Date.parse("yyyy-MM-dd", "2024-01-01")
def toDate = Date.parse("yyyy-MM-dd", "2025-12-31")
def period = "monthly" // Options: "daily", "weekly", "monthly"

// Run the allocation migration job
def migration = new AllocationMigrationJob(sql, project, resource, fromDate, toDate, period)
migration.processAllocations()

// Close the database connection after use
connection.close()




