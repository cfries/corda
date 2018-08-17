package net.corda.node.persistence

import net.corda.client.rpc.CordaRPCClient
import net.corda.core.internal.packageName
import net.corda.core.messaging.startFlow
import net.corda.core.utilities.getOrThrow
import net.corda.node.services.Permissions
import net.corda.testMessage.Message
import net.corda.testMessage.MessageState
import net.corda.testing.core.singleIdentity
import net.corda.testing.driver.DriverParameters
import net.corda.testing.driver.driver
import net.corda.testing.node.User
import org.junit.Test
import java.nio.file.Path
import java.sql.DriverManager
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class MigrateIncompatibleDatabaseTableOrColumnNameTests {
    @Test
    fun `node migrates node_attachemnts_contranst_class_name table name from version 3 dot 0`() {
        `node fixes incompatible table name`("NODE_ATTACHMENTS_CONTRACTS", "NODE_ATTACHMENTS_CONTRACT_CLASS_NAME")
    }

    @Test
    fun `node migrates node_attchemnts_contransts table name from version 3 dot 1`() {
        `node fixes incompatible table name`("NODE_ATTACHMENTS_CONTRACTS", "NODE_ATTCHMENTS_CONTRACTS")
    }

    private fun `node fixes incompatible table name`(tableNameFromMapping: String, tableNameInDB: String) {
        val user = User("mark", "dadada", setOf(Permissions.startFlow<SendMessageFlow>(), Permissions.invokeRpc("vaultQuery")))
        val message = Message("Hello world!")
        val baseDir: Path = driver(DriverParameters(
                inMemoryDB = false,
                startNodesInProcess = isQuasarAgentSpecified(),
                extraCordappPackagesToScan = listOf(MessageState::class.packageName)
        )) {
            val (nodeName, baseDir) = {
                val nodeHandle = startNode(rpcUsers = listOf(user)).getOrThrow()
                val nodeName = nodeHandle.nodeInfo.singleIdentity().name
                CordaRPCClient(nodeHandle.rpcAddress).start(user.username, user.password).use {
                    it.proxy.startFlow(::SendMessageFlow, message, defaultNotaryIdentity).returnValue.getOrThrow()
                }
                nodeHandle.stop()
                Pair(nodeName, nodeHandle.baseDirectory)
            }()

            // replace the correct table name with one from the former release
            DriverManager.getConnection("jdbc:h2:file://$baseDir/persistence", "sa", "").use {
                it.createStatement().execute("ALTER TABLE $tableNameFromMapping RENAME TO $tableNameInDB")
                it.createStatement().execute("DROP TABLE DATABASECHANGELOGLOCK")
                it.createStatement().execute("DROP TABLE DATABASECHANGELOG")
                it.commit()
            }

            startNode(providedName = nodeName, rpcUsers = listOf(user)).getOrThrow()
            baseDir
        }

        // check that the node did recreated the correct table matching it's entity mapping
        DriverManager.getConnection("jdbc:h2:file://$baseDir/persistence", "sa", "").use {
            assertTrue(it.metaData.getTables(null, null, tableNameFromMapping, null).next())
            assertFalse(it.metaData.getTables(null, null, tableNameInDB, null).next())
            it.createStatement().executeQuery("SELECT COUNT(*) FROM $tableNameFromMapping").use {
                assertTrue(it.next())
                assertEquals(1, it.getInt(1))
            }
        }
    }
}