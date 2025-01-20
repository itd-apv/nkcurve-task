import com.niku.xmlserver.blob.NkCurve
import com.niku.xmlserver.blob.NkSegment
import groovy.sql.Sql
import groovy.time.TimeCategory
import java.sql.DriverManager
import java.sql.Connection
import java.sql.Blob

class AllocationSample {

    Sql sql
    String period
    def projectResourcePairs  // List of project-resource pairs (with project dates)

    // Constructor to initialize parameters
    AllocationSample(Sql sql, String period, def projectResourcePairs) {
        this.sql = sql
        this.period = period
        this.projectResourcePairs = projectResourcePairs
    }

    // Method to fetch the pralloccurve for the resource
    NkCurve getAllocationCurveForResource(String resource) {
        if (fetchedResources.contains(resource)) {
            println "Skipping resource ${resource} as it has already been fetched."
            return null
        }

        fetchedResources.add(resource)

        def query = """
        SELECT pralloccurve
        FROM prteam
        WHERE prresourceid = (SELECT id FROM srm_resources WHERE unique_name = ?)
    """
        def result = sql.firstRow(query, [resource])

        if (result && result["pralloccurve"] instanceof Blob) {
            println "Allocation curve (pralloccurve) for resource ${resource} fetched successfully."

            Blob blob = result["pralloccurve"]
            byte[] byteArray = extractBlobData(blob)

            if (byteArray.size() == 0) {
                println "No data found in pralloccurve for resource ${resource}."
                return null
            }

            return new NkCurve(byteArray)
        } else {
            println "No pralloccurve found for resource ${resource}."
            return null
        }
    }

    // Set to keep track of fetched resources
    Set<String> fetchedResources = [] // Store unique resources that have been processed

    // Helper method to extract byte data from the Blob
    byte[] extractBlobData(Blob blob) {
        try {
            InputStream inputStream = blob.getBinaryStream()
            byte[] byteArray = inputStream.bytes
            inputStream.close()

            return byteArray
        } catch (Exception e) {
            println "Error extracting Blob data: ${e.message}"
            return []  // Return an empty byte array in case of error
        }
    }

    // Method to process allocations for each resource and group them by project
    void processAllocations() {
        def projectResourceMap = [:]

        // Collecting data for each project and its resources
        projectResourcePairs.each { pair ->
            String project = pair.projectId.toString()
            String resource = pair.resourceName
            Date projectFromDate = pair.fromDate
            Date projectToDate = pair.toDate

            // Add resources to the map under the respective project if not present
            if (!projectResourceMap.containsKey(project)) {
                projectResourceMap[project] = []
            }

            NkCurve softCurve = getAllocationCurveForResource(resource)
            NkCurve hardCurve = getAllocationCurveForResource(resource)

            // Handle missing curves
            if (softCurve == null || hardCurve == null) {
                println "Skipping resource ${resource} due to missing allocation curves."
                return
            }

            // Collect allocation data for this resource
            List<Map<String, Object>> allocationData = []

            use(TimeCategory) {
                Date currentDate = projectFromDate
                while (currentDate <= projectToDate) {
                    def nextDate = getNextPeriod(currentDate)

                    def softAllocation = getAllocationForPeriod(softCurve, currentDate, nextDate)
                    def hardAllocation = getAllocationForPeriod(hardCurve, currentDate, nextDate)

                    allocationData.add([
                            project: project,
                            resource: resource,
                            segmentStart: currentDate.format('dd.MMM.yyyy'),
                            segmentEnd: nextDate.format('dd.MMM.yyyy'),
                            hardAllocation: hardAllocation,
                            softAllocation: softAllocation
                    ])

                    currentDate = nextDate
                }
            }

            // Add this resource's allocation data to the project map
            projectResourceMap[project] << allocationData
        }

        // Writing grouped allocation data to CSV for each project
        projectResourceMap.each { project, resourcesData ->
            resourcesData.each { allocationData ->
                writeToCSV(allocationData, "resource_allocations_${project}_${fromDate.format('yyyyMMdd')}_${toDate.format('yyyyMMdd')}.csv")
            }
        }
    }

    // Method to write the allocation data to a CSV file
    void writeToCSV(List<Map<String, Object>> data, String filename) {
        try {
            File file = new File(filename)
            BufferedWriter writer = new BufferedWriter(new FileWriter(file))

            // Writing header to the CSV
            writer.write("Project,Resource,Segment Start,Segment End,Hard Allocation (PD),Soft Allocation (PD)\n")

            // Writing the allocation data for each row
            data.each { entry ->
                writer.write("${entry.project},${entry.resource},${entry.segmentStart},${entry.segmentEnd},${entry.hardAllocation},${entry.softAllocation}\n")
            }

            writer.close()
            println "CSV file written successfully: ${filename}"
        } catch (IOException e) {
            println "Error writing to CSV: ${e.message}"
        }
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
def period = "monthly" // Options: "daily", "weekly", "monthly"

// Fetch project-resource pairs including from and to dates
def query = """
    SELECT pt.prprojectid AS projectId, sr.UNIQUE_NAME AS resourceName, iv.schedule_start AS fromDate, iv.schedule_finish AS toDate
    FROM prteam pt
    JOIN srm_resources sr ON pt.prresourceid = sr.id
    JOIN inv_investments iv ON pt.prprojectid = iv.id
"""
def projectResourcePairs = sql.rows(query)

def migration = new AllocationSample(sql, period, projectResourcePairs)
migration.processAllocations()

// Close the database connection after use
connection.close()
