package ru.creditcabinet.documents

import com.fasterxml.jackson.module.kotlin.jacksonObjectMapper
import io.grpc.Status
import io.grpc.StatusRuntimeException
import io.grpc.TlsChannelCredentials
import io.grpc.netty.shaded.io.grpc.netty.NettyChannelBuilder
import ru.creditcabinet.contracts.documents.v1.DocumentsServiceGrpc
import ru.creditcabinet.contracts.documents.v1.Scope
import ru.creditcabinet.document.generated.ArtifactKind
import ru.creditcabinet.document.generated.ArtifactInput
import ru.creditcabinet.document.generated.ArtifactPhase
import ru.creditcabinet.documentsgrpc.DocumentsGrpcClient
import ru.creditcabinet.documentsgrpc.documentsTlsChannel
import ru.creditcabinet.product.domain.LoanMath
import java.io.File
import java.math.BigDecimal
import java.net.URI
import java.net.http.HttpClient
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.sql.DriverManager
import java.time.Instant
import java.util.Base64
import java.util.UUID
import java.util.concurrent.TimeUnit
import javax.crypto.Mac
import javax.crypto.spec.SecretKeySpec
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/** Required gate: missing infrastructure is a failure, never a skipped test. */
class DocumentsRuntimeTest {
    private val mapper = jacksonObjectMapper().findAndRegisterModules().disable(com.fasterxml.jackson.databind.SerializationFeature.WRITE_DATES_AS_TIMESTAMPS)
    private val certs = File(env("DOCUMENTS_TEST_CERTS"))
    private val port = env("DOCUMENTS_TEST_GRPC_PORT").toInt()
    private val scope = DocumentScope(UUID.fromString(env("DOCUMENTS_TEST_COMPANY")), UUID.fromString(env("DOCUMENTS_TEST_APPLICATION")))
    private fun client(peer: String = "credit", epoch: Long = 2, action: (DocumentsGrpcClient) -> Unit) {
        val channel = documentsTlsChannel("127.0.0.1", port, certs.resolve("$peer.crt"), certs.resolve("$peer.key"), certs.resolve("ca.crt"))
        try {
            action(DocumentsGrpcClient(channel, mapper, epoch, 5000))
        } finally {
            channel.shutdownNow()
        }
    }

    @Test
    fun `mTLS scope epoch immutable bytes and scanner rejection are enforced by separate runtime`() {
        val bytes = "%PDF-1.4\nDocuments gate\n%%EOF".toByteArray()
        val id = UUID.randomUUID()
        val record = DocumentRecord(
            id,
            scope,
            "OTHER",
            "proof.pdf",
            "$id.pdf",
            bytes.size.toLong(),
            UUID.randomUUID(),
            Instant.now(),
            "BUSINESS",
            env("DOCUMENTS_TEST_STORAGE_BACKEND"),
            sha256(bytes),
            false,
        )
        client { remote ->
            remote.put(record, bytes)
            remote.put(record, bytes)
            assertEquals(1, remote.list(scope).count { it.id == id })
            assertContentEquals(bytes, remote.read(scope, id, "BUSINESS"))
            assertFailsWith<StatusRuntimeException> { remote.read(scope.copy(companyId = UUID.randomUUID()), id, "BUSINESS") }
            assertFailsWith<StatusRuntimeException> { remote.read(scope.copy(applicationId = UUID.randomUUID()), id, "BUSINESS") }
            assertFailsWith<StatusRuntimeException> { remote.read(scope, id, "CHAT") }
            assertFailsWith<StatusRuntimeException> { remote.put(record.copy(fileName = "changed.pdf"), bytes) }
            val infected = "EICAR-STANDARD-ANTIVIRUS-TEST-FILE".toByteArray()
            assertFailsWith<StatusRuntimeException> {
                remote.put(
                    record.copy(
                        id = UUID.randomUUID(),
                        storedName = "infected.pdf",
                        sizeBytes = infected.size.toLong(),
                        checksum = sha256(infected),
                    ),
                    infected,
                )
            }
            assertTrue(remote.list(scope).none { it.storedName == "infected.pdf" })
            val unavailable = "GATE-SCANNER-UNAVAILABLE".toByteArray()
            assertFailsWith<StatusRuntimeException> {
                remote.put(
                    record.copy(
                        id = UUID.randomUUID(),
                        storedName = "unavailable.pdf",
                        sizeBytes = unavailable.size.toLong(),
                        checksum = sha256(unavailable),
                    ),
                    unavailable,
                )
            }
            assertTrue(remote.list(scope).none { it.storedName == "unavailable.pdf" })
        }
        client("wrong") { remote -> assertEquals(Status.Code.PERMISSION_DENIED, assertFailsWith<StatusRuntimeException> { remote.list(scope) }.status.code) }
        client(epoch = 1) { remote -> assertFailsWith<StatusRuntimeException> { remote.list(scope) } }
        val noCertificate = NettyChannelBuilder.forAddress(
            "127.0.0.1",
            port,
            TlsChannelCredentials.newBuilder().trustManager(certs.resolve("ca.crt")).build(),
        ).build()
        try {
            assertFailsWith<StatusRuntimeException> {
                DocumentsServiceGrpc.newBlockingStub(noCertificate).withDeadlineAfter(
                    3,
                    TimeUnit.SECONDS,
                ).listDocuments(Scope.getDefaultInstance())
            }
        } finally {
            noCertificate.shutdownNow()
        }
        val plaintext = NettyChannelBuilder.forAddress("127.0.0.1", port).usePlaintext().build()
        try {
            val denied = assertFailsWith<StatusRuntimeException> {
                DocumentsServiceGrpc.newBlockingStub(plaintext).withDeadlineAfter(3, TimeUnit.SECONDS).listDocuments(
                    Scope.newBuilder().setCompanyId(scope.companyId.toString()).setApplicationId(scope.applicationId.toString()).setEpoch(2).build(),
                )
            }
            assertEquals(Status.Code.UNAVAILABLE, denied.status.code)
        } finally {
            plaintext.shutdownNow()
        }
    }

    @Test
    fun `frozen generation survives concurrent retries and publishes exactly one readiness row`() {
        client { remote ->
            val offer = UUID.randomUUID()
            val text = "Сохранённые условия: 123456 рублей, 6 месяцев, 12 процентов."
            val input = ArtifactInput(
                scope.applicationId, 777, offer, "Синтетический заёмщик", "000100000004", "Сохранённый продукт", 123456, 6,
                LoanMath.calculate(123456, BigDecimal("12.00"), 6, false), ArtifactPhase.FINAL, text, sha256(text.toByteArray()), false,
                Instant.parse("2026-11-27T00:00:00Z"), "proof-contract", Instant.parse("2026-11-26T00:00:00Z"),
            )
            val frozen = mapper.writeValueAsString(input)
            val id = UUID.randomUUID()
            val record = ArtifactRecord(
                id, scope, offer, "CONTRACT_PDF", "FINAL", 2, sha256(frozen.toByteArray()), frozen,
                "Сохранённые условия", "contract.pdf", "application/pdf", "generated-$id.pdf", env("DOCUMENTS_TEST_STORAGE_BACKEND"), Instant.now(),
            )
            val decision = UUID.randomUUID()
            db("credit") { connection ->
                connection.prepareStatement(
                    "insert into decisions(id,application_id,rule_set_version,decided_by_type,outcome,inputs,created_at) values(?,?,1,'SYSTEM','APPROVE','{}',now())",
                ).use { statement ->
                    statement.setObject(1, decision)
                    statement.setObject(2, scope.applicationId)
                    statement.executeUpdate()
                }
                connection.prepareStatement(
                    "insert into offers(id,application_id,decision_id,kind,product_code,amount,term_months,rate,monthly_payment,total_payment,valid_until,status,created_at,product_version) values(?,?,?,'APPROVED','WORKING_CAPITAL',123456,6,12,21000,126000,now()+interval '1 day','ACCEPTED',now(),1)",
                ).use { statement ->
                    statement.setObject(1, offer)
                    statement.setObject(2, scope.applicationId)
                    statement.setObject(3, decision)
                    statement.executeUpdate()
                }
            }
            for (kind in listOf("SCHEDULE_PDF", "SCHEDULE_XLSX")) {
                val nextId = UUID.randomUUID()
                val typedKind = ru.creditcabinet.document.generated.ArtifactKind.valueOf(kind)
                remote.prepare(
                    record.copy(
                        id = nextId,
                        kind = kind,
                        fileName = "schedule.${typedKind.extension}",
                        contentType = typedKind.contentType,
                        storedName = "generated-$nextId.${typedKind.extension}",
                    ),
                )
            }
            db("credit") { connection ->
                connection.prepareStatement(
                    "insert into document_artifact_inputs(application_id,offer_id,phase,input_hash,input_json,delivered_at) values(?,?,'FINAL',?,?::jsonb,now())",
                ).use { statement ->
                    statement.setObject(
                        1,
                        scope.applicationId,
                    )
                    statement.setObject(
                        2,
                        offer,
                    )
                    statement.setString(
                        3,
                        record.inputHash,
                    )
                    statement.setString(
                        4,
                        frozen,
                    )
                    statement.executeUpdate()
                }
            }
            db("credit") { connection ->
                assertFailsWith<java.sql.SQLException> {
                    connection.prepareStatement(
                        "update document_artifact_inputs set input_json=jsonb_set(input_json,'{calculation,rate}','12.0'::jsonb) where application_id=? and offer_id=?",
                    ).use { statement ->
                        statement.setObject(1, scope.applicationId)
                        statement.setObject(2, offer)
                        statement.executeUpdate()
                    }
                }
            }
            val first = remote.prepare(record)
            val duplicateId = UUID.randomUUID()
            assertEquals(first.id, remote.prepare(record.copy(id = duplicateId, storedName = "generated-$duplicateId.pdf")).id)
            assertFailsWith<StatusRuntimeException> { remote.prepare(record.copy(id = UUID.randomUUID(), inputJson = frozen.replace("123456", "999999"))) }
            val deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(40)
            var ready = first
            while (ready.status != "READY" && System.nanoTime() < deadline) {
                Thread.sleep(200)
                ready = remote.artifacts(scope).single { it.id == first.id }
            }
            assertEquals("READY", ready.status)
            val bytes = remote.artifactBytes(scope, ready.id)
            assertEquals(ready.checksum, sha256(bytes))
            assertEquals(ready, remote.retry(scope, ready.id))
            assertContentEquals(bytes, remote.artifactBytes(scope, ready.id))
            assertEquals(frozen, ready.inputJson)
            assertTrue(ready.inputJson.contains("\"rate\":12.00"))
            assertEquals(input, mapper.readValue(ready.inputJson, ArtifactInput::class.java))
            assertEquals(2, mapper.readValue(ready.inputJson, ArtifactInput::class.java).calculation.rate.scale())
            val noticeDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(40)
            var notices = 0
            while (notices == 0 && System.nanoTime() < noticeDeadline) {
                Thread.sleep(200)
                db("credit") { connection ->
                    connection.prepareStatement("select count(*) from generated_artifact_notices where application_id=? and offer_id=?").use { statement ->
                        statement.setObject(1, scope.applicationId)
                        statement.setObject(2, offer)
                        statement.executeQuery().use { rs ->
                            rs.next()
                            notices = rs.getInt(1)
                        }
                    }
                }
            }
            assertEquals(1, notices)
            db("credit") { connection ->
                connection.prepareStatement("select count(*) from delivery_outbox where channel='notification' and event_key like ?").use { statement ->
                    statement.setString(1, "generated-files:${scope.applicationId}:$offer:%")
                    statement.executeQuery().use { rs ->
                        rs.next()
                        assertEquals(1, rs.getInt(1))
                    }
                }
            }
            // Persist a worker claim that will expire only after the harness stops both JARs.
            val recoveryId = UUID.randomUUID()
            val recoveryInput = input.copy(offerId = UUID.randomUUID())
            db("credit") { connection ->
                connection.prepareStatement(
                    "insert into offers(id,application_id,decision_id,kind,product_code,amount,term_months,rate,monthly_payment,total_payment,valid_until,status,created_at,product_version) values(?,?,?,'APPROVED','WORKING_CAPITAL',123456,6,12,21000,126000,now()+interval '1 day','ACCEPTED',now(),1)",
                ).use { statement ->
                    statement.setObject(
                        1,
                        recoveryInput.offerId,
                    )
                    statement.setObject(
                        2,
                        scope.applicationId,
                    )
                    statement.setObject(
                        3,
                        decision,
                    )
                    statement.executeUpdate()
                }
            }
            val legacyInput = recoveryInput.copy(
                phase = ArtifactPhase.PREVIEW,
                contractText = "",
                contractTextHash = null,
                signedAt = null,
                contractNumber = null,
            )
            val legacyJson = mapper.writeValueAsString(legacyInput)
            val legacyId = UUID.randomUUID()
            val legacy = record.copy(
                id = legacyId, offerId = legacyInput.offerId, phase = "PREVIEW", kind = "SCHEDULE_PDF", templateVersion = 1,
                inputJson = legacyJson, inputHash = sha256(legacyJson.toByteArray()), status = "ERROR", storedName = "generated-$legacyId.pdf",
            )
            db("documents") { connection ->
                connection.prepareStatement(
                    "insert into artifact_records(id,company_id,application_id,offer_id,kind,phase,template_version,input_hash,metadata,status,attempts) values(?,?,?,?,'SCHEDULE_PDF','PREVIEW',1,?,?::jsonb,'ERROR',5)",
                ).use { statement ->
                    statement.setObject(1, legacy.id)
                    statement.setObject(2, scope.companyId)
                    statement.setObject(3, scope.applicationId)
                    statement.setObject(4, legacy.offerId)
                    statement.setString(5, legacy.inputHash)
                    statement.setString(6, mapper.writeValueAsString(legacy))
                    statement.executeUpdate()
                }
            }
            registerArtifactReference(legacy)
            val replacement = remote.retry(scope, legacyId)
            assertTrue(replacement.id != legacyId)
            assertEquals(2, replacement.templateVersion)
            assertEquals(legacyJson, replacement.inputJson)
            assertEquals(legacy.inputHash, replacement.inputHash)
            // An independently READY remote orphan cannot be downloaded/retried/notified or reversed.
            val orphanJson = mapper.writeValueAsString(input.copy(companyName = "Uncommitted artifact"))
            val orphanIds = ArtifactKind.entries.map { kind ->
                val orphanId = UUID.randomUUID()
                remote.prepare(record.copy(id = orphanId, kind = kind.name, inputJson = orphanJson, inputHash = sha256(orphanJson.toByteArray()), title = "Uncommitted artifact", fileName = "orphan.${kind.extension}", contentType = kind.contentType, storedName = "generated-$orphanId.${kind.extension}"))
                orphanId
            }
            val orphanDeadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(40)
            while (remote.artifacts(scope).filter { it.id in orphanIds }.any { it.status != "READY" } && System.nanoTime() < orphanDeadline) Thread.sleep(200)
            assertEquals(3, remote.artifacts(scope).count { it.id in orphanIds && it.status == "READY" })
            val auth = token(UUID.randomUUID(), "CLIENT", scope.companyId)
            orphanIds.forEach { orphanId ->
                assertEquals(404, http("/api/v1/applications/${scope.applicationId}/artifacts/$orphanId/download", auth).statusCode())
                assertEquals(404, http("/api/v1/applications/${scope.applicationId}/artifacts/$orphanId/retry", auth, "{}").statusCode())
            }
            val recoveryJson = mapper.writeValueAsString(recoveryInput)
            val recovery = record.copy(
                id = recoveryId,
                offerId = recoveryInput.offerId,
                inputJson = recoveryJson,
                inputHash = sha256(recoveryJson.toByteArray()),
                storedName = "generated-$recoveryId.pdf",
            )
            registerArtifactReference(recovery)
            db("documents") { connection ->
                connection.prepareStatement(
                    "insert into artifact_records(id,company_id,application_id,offer_id,kind,phase,template_version,input_hash,metadata,lease_token,lease_until,next_attempt_at) values(?,?,?,?,'CONTRACT_PDF','FINAL',2,?,?::jsonb,?,now()+interval '1 day',now()+interval '1 day')",
                ).use { statement ->
                    statement.setObject(
                        1,
                        recovery.id,
                    )
                    statement.setObject(
                        2,
                        scope.companyId,
                    )
                    statement.setObject(
                        3,
                        scope.applicationId,
                    )
                    statement.setObject(
                        4,
                        recovery.offerId,
                    )
                    statement.setString(
                        5,
                        recovery.inputHash,
                    )
                    statement.setString(
                        6,
                        mapper.writeValueAsString(recovery),
                    )
                    statement.setObject(
                        7,
                        UUID.randomUUID(),
                    )
                    statement.executeUpdate()
                }
            }

            db("documents") { connection ->
                connection.prepareStatement("select count(*) from artifact_outbox where artifact_id=?").use { statement ->
                    statement.setObject(1, ready.id)
                    statement.executeQuery().use { rs ->
                        rs.next()
                        assertEquals(1, rs.getInt(1))
                    }
                }
            }
        }
    }

    @Test
    fun `Credit HTTP rejects hidden packet private attachment wrong company role and revoked agent`() {
        val actor = UUID.randomUUID()
        val hidden = UUID.fromString(env("DOCUMENTS_TEST_HIDDEN"))
        val visible = UUID.fromString(env("DOCUMENTS_TEST_VISIBLE"))
        val privateId = UUID.fromString(env("DOCUMENTS_TEST_PRIVATE"))
        val base = "/api/v1/applications/${scope.applicationId}"
        val client = token(actor, "CLIENT", scope.companyId)
        assertEquals(200, http("$base/documents/$visible", client).statusCode())
        assertEquals(404, http("$base/artifacts/${UUID.randomUUID()}/retry", client, "{}").statusCode())
        for (id in listOf(hidden, privateId)) {
            if (id == hidden) assertEquals(404, http("$base/documents/$id", client).statusCode())
            val body = """{"idempotencyKey":"${UUID.randomUUID()}","text":"Проверка доступа","documentIds":["$id"]}"""
            assertEquals(404, http("$base/messages", client, body).statusCode())
            db("credit") { connection ->
                connection.prepareStatement("select count(*) from message_attachments where document_id=?").use { statement ->
                    statement.setObject(1, id)
                    statement.executeQuery().use { rs ->
                        rs.next()
                        assertEquals(0, rs.getInt(1))
                    }
                }
            }
        }
        assertEquals(200, http("$base/documents/$privateId", client).statusCode())
        for (role in listOf("MANAGER", "UNDERWRITER", "RISK_ADMIN")) {
            val bank = token(UUID.randomUUID(), role, null)
            assertEquals(200, http("$base/documents/$visible", bank).statusCode())
            assertEquals(200, http("$base/documents/$privateId", bank).statusCode())
            assertEquals(404, http("$base/documents/$hidden", bank).statusCode())
        }
        assertEquals(401, http("$base/documents/$visible", "").statusCode())
        assertEquals(404, http("$base/documents/$visible", token(actor, "CLIENT", UUID.randomUUID())).statusCode())
        assertEquals(404, http("$base/documents/$visible", token(actor, "UNKNOWN", scope.companyId)).statusCode())
        assertEquals(
            404,
            http(
                "/api/v1/agent/applications/${scope.applicationId}/documents/$visible",
                token(UUID.fromString(env("DOCUMENTS_TEST_REVOKED_AGENT")), "AGENT", null),
            ).statusCode(),
        )
        assertEquals(404, http("/internal/documents/v1", client).statusCode())
        val bank = token(UUID.randomUUID(), "UNDERWRITER", null)
        assertEquals(200, http("/api/v1/applications", bank).statusCode())
        assertEquals(200, http(base, bank).statusCode())
        assertEquals(200, http("$base/financing-scenario", client).statusCode())
        val uploaded = multipart("$base/messages/attachments", client, UUID.randomUUID().toString(), "chat.pdf", "%PDF-1.4\nchat\n%%EOF".toByteArray())
        assertEquals(200, uploaded.statusCode(), String(uploaded.body()))
        val chatId = mapper.readTree(uploaded.body())["id"].asText()
        assertEquals(
            200,
            http("$base/messages", client, """{"idempotencyKey":"${UUID.randomUUID()}","text":"Remote attachment","documentIds":["$chatId"]}""").statusCode(),
        )
        val messages = http("$base/messages", bank)
        assertEquals(200, messages.statusCode())
        assertTrue(String(messages.body()).contains(chatId))
        assertEquals(200, http("$base/documents/$chatId", bank).statusCode())
    }

    @Test
    fun `runtime roles cannot change fences or connect to the other business database`() {
        val base = env("DOCUMENTS_TEST_DATABASE_BASE")
        val documentsPassword = env("DOCUMENTS_TEST_DOCUMENTS_APP_PASSWORD")
        val creditPassword = env("DOCUMENTS_TEST_CREDIT_APP_PASSWORD")
        DriverManager.getConnection("$base/documents", "documents_app", documentsPassword).use { connection ->
            connection.createStatement().use { statement ->
                statement.executeQuery("select public.assert_documents_writer(2)").use { rs ->
                    rs.next()
                    assertTrue(rs.getBoolean(1))
                }
                statement.executeQuery("select current_user").use { rs ->
                    rs.next()
                    assertEquals("documents_app", rs.getString(1))
                }
                val denied = assertFailsWith<java.sql.SQLException> { statement.executeUpdate("update document_ownership set epoch=epoch+1") }
                assertEquals("42501", denied.sqlState)
            }
        }
        DriverManager.getConnection("$base/credit", "credit_app", creditPassword).use { connection ->
            connection.createStatement().use { statement ->
                val denied = assertFailsWith<java.sql.SQLException> { statement.executeUpdate("update document_ownership set epoch=epoch+1") }
                assertEquals("42501", denied.sqlState)
                for (table in listOf("documents", "generated_artifacts")) {
                    val oldReader = assertFailsWith<java.sql.SQLException> { statement.executeQuery("select * from $table limit 1") }
                    assertEquals("42501", oldReader.sqlState)
                }
                val fenced = assertFailsWith<java.sql.SQLException> { statement.executeQuery("select public.assert_local_documents_writer()") }
                assertEquals("55000", fenced.sqlState)
            }
        }
        assertFailsWith<java.sql.SQLException> { DriverManager.getConnection("$base/credit", "documents_app", documentsPassword).close() }
        assertFailsWith<java.sql.SQLException> { DriverManager.getConnection("$base/documents", "credit_app", creditPassword).close() }
    }

    @Test
    fun `Credit rollback and lost reply keep uploads invisible until committed reference and replay exact owner metadata`() {
        val application = UUID.randomUUID()
        val company = UUID.randomUUID()
        val actor = UUID.randomUUID()
        val auth = token(actor, "CLIENT", company)
        val documentScope = DocumentScope(company, application)
        val base = "/api/v1/applications/$application"
        db("credit") { connection ->
            connection.createStatement().use {
                it.executeUpdate(
                    "insert into applications(id,company_id,company_inn,company_name,legal_form,created_by,product_code,purpose,amount,term_months,status,created_at,updated_at,status_changed_at,product_catalog_snapshot) values('$application','$company','000100000004','Upload rollback','ENTREPRENEUR','$actor','WORKING_CAPITAL','CASH_GAP',123456,6,'DRAFT',now(),now(),now(),'{\"WORKING_CAPITAL\":1}')",
                )
            }
        }
        for ((index, lostReply) in listOf(false, true, false).withIndex()) {
            val key = UUID.randomUUID().toString()
            val fileName = if (index == 2) {
                "abandoned-upload.pdf"
            } else if (lostReply) {
                "timeout-rollback.pdf"
            } else {
                "journal-rollback.pdf"
            }
            val bytes = "%PDF-1.4\n$fileName\n%%EOF".toByteArray()
            db("credit") { connection ->
                connection.createStatement().use { statement ->
                    statement.execute(
                        "create function documents_gate_journal_failure() returns trigger language plpgsql as $$ begin if new.application_id='$application' and new.payload->>'fileName'='$fileName' then perform pg_sleep(${if (lostReply) 2 else 0}); raise exception 'Synthetic journal rollback'; end if; return new; end $$",
                    )
                    statement.execute(
                        "create trigger documents_gate_journal_failure before insert on application_events for each row execute function documents_gate_journal_failure()",
                    )
                }
            }
            try {
                if (lostReply) {
                    assertFailsWith<java.net.http.HttpTimeoutException> { multipart("$base/documents?type=OTHER", auth, key, fileName, bytes, 300) }
                    Thread.sleep(3000)
                } else {
                    assertEquals(500, multipart("$base/documents?type=OTHER", auth, key, fileName, bytes).statusCode())
                }
                var orphan: DocumentRecord? = null
                client { remote -> orphan = remote.list(documentScope).single { it.fileName == fileName } }
                val stored = orphan!!
                assertEquals(404, http("$base/documents/${stored.id}", auth).statusCode())
                val view = http(base, auth)
                assertEquals(200, view.statusCode())
                assertTrue(mapper.readTree(view.body())["documents"].none { it["id"].asText() == stored.id.toString() })
                db("credit") { connection ->
                    connection.createStatement().use { statement ->
                        statement.executeQuery("select count(*) from document_references where document_id='${stored.id}'").use { rs ->
                            rs.next()
                            assertEquals(0, rs.getInt(1))
                        }
                    }
                }
            } finally {
                db("credit") { connection ->
                    connection.createStatement().use {
                        it.execute("drop trigger documents_gate_journal_failure on application_events; drop function documents_gate_journal_failure()")
                    }
                }
            }
            if (index == 2) continue
            var before: DocumentRecord? = null
            client { remote -> before = remote.list(documentScope).single { it.fileName == fileName } }
            val retry = multipart("$base/documents?type=OTHER", auth, key, fileName, bytes)
            assertEquals(201, retry.statusCode(), String(retry.body()))
            val actual = mapper.readTree(retry.body())
            assertEquals(before!!.id.toString(), actual["id"].asText())
            assertEquals(before!!.uploadedAt, Instant.parse(actual["uploadedAt"].asText()))
            assertContentEquals(retry.body(), multipart("$base/documents?type=OTHER", auth, key, fileName, bytes).body())
            assertEquals(409, multipart("$base/documents?type=OTHER", auth, key, fileName, "%PDF-1.4\nchanged\n%%EOF".toByteArray()).statusCode())
            client { remote -> assertEquals(before, remote.list(documentScope).single { it.fileName == fileName }) }
            db("credit") { connection ->
                connection.createStatement().use { statement ->
                    statement.executeQuery(
                        "select count(*) from application_events where application_id='$application' and event='DOCUMENT_ADDED' and payload->>'documentId'='${before!!.id}'",
                    ).use { rs ->
                        rs.next()
                        assertEquals(1, rs.getInt(1))
                    }
                }
            }
        }
    }

    @Test
    fun `delete journal rollback preserves metadata and committed removal survives remote outage and reverse`() {
        val application = UUID.randomUUID()
        val company = UUID.randomUUID()
        val actor = UUID.randomUUID()
        val auth = token(actor, "CLIENT", company)
        val base = "/api/v1/applications/$application"
        db("credit") { connection -> connection.createStatement().use { it.executeUpdate("insert into applications(id,company_id,company_inn,company_name,legal_form,created_by,product_code,purpose,amount,term_months,status,created_at,updated_at,status_changed_at,product_catalog_snapshot) values('$application','$company','000100000004','Deletion rollback','ENTREPRENEUR','$actor','WORKING_CAPITAL','CASH_GAP',123456,6,'DRAFT',now(),now(),now(),'{\"WORKING_CAPITAL\":1}')") } }
        for (deferred in listOf(false, true)) {
            val uploaded = multipart("$base/documents?type=OTHER", auth, UUID.randomUUID().toString(), "deletion.pdf", "%PDF-1.4\ndelete\n%%EOF".toByteArray())
            assertEquals(201, uploaded.statusCode())
            val id = UUID.fromString(mapper.readTree(uploaded.body())["id"].asText())
            db("credit") { connection -> connection.createStatement().use { statement ->
                statement.execute("create function documents_gate_delete_rollback() returns trigger language plpgsql as $$ begin if new.application_id='$application' and new.event='DOCUMENT_REMOVED' then raise exception 'Synthetic delete journal rollback'; end if; return new; end $$; create trigger documents_gate_delete_rollback before insert on application_events for each row execute function documents_gate_delete_rollback()")
            } }
            try {
                assertEquals(500, delete("$base/documents/$id", auth).statusCode())
                assertEquals(200, http("$base/documents/$id", auth).statusCode())
                client { remote -> assertEquals(null, remote.list(DocumentScope(company, application)).single { it.id == id }.deletedAt) }
                db("credit") { connection -> connection.createStatement().use { statement -> statement.executeQuery("select count(*) from document_deletion_intents where document_id='$id'").use { rs -> rs.next(); assertEquals(0, rs.getInt(1)) } } }
            } finally {
                db("credit") { connection -> connection.createStatement().use { it.execute("drop trigger documents_gate_delete_rollback on application_events; drop function documents_gate_delete_rollback()") } }
            }
            assertEquals(204, delete("$base/documents/$id", auth).statusCode())
            assertEquals(404, http("$base/documents/$id", auth).statusCode())
            client { remote -> assertEquals(null, remote.list(DocumentScope(company, application)).single { it.id == id }.deletedAt) }
            if (deferred) db("credit") { connection -> connection.createStatement().use { it.executeUpdate("update document_deletion_intents set run_at=now()+interval '1 hour' where document_id='$id'") } }
        }
    }

    private fun delete(path: String, token: String): HttpResponse<ByteArray> = HttpClient.newHttpClient().send(
        HttpRequest.newBuilder(URI.create(env("DOCUMENTS_TEST_CREDIT_URL") + path)).timeout(java.time.Duration.ofSeconds(5)).header("Authorization", "Bearer $token").DELETE().build(),
        HttpResponse.BodyHandlers.ofByteArray(),
    )

    private fun multipart(path: String, token: String, key: String, name: String, bytes: ByteArray, timeoutMs: Long = 10000): HttpResponse<ByteArray> {
        val boundary = "documents-runtime-${UUID.randomUUID()}"
        val prefix = "--$boundary\r\nContent-Disposition: form-data; name=\"file\"; filename=\"$name\"\r\nContent-Type: application/pdf\r\n\r\n".toByteArray()
        val body = prefix + bytes + "\r\n--$boundary--\r\n".toByteArray()
        val request = HttpRequest.newBuilder(URI.create(env("DOCUMENTS_TEST_CREDIT_URL") + path))
            .timeout(java.time.Duration.ofMillis(timeoutMs)).header("Authorization", "Bearer $token")
            .header("Idempotency-Key", key).header("Content-Type", "multipart/form-data; boundary=$boundary")
            .POST(HttpRequest.BodyPublishers.ofByteArray(body)).build()
        return HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofByteArray())
    }

    private fun http(path: String, token: String, body: String? = null): HttpResponse<ByteArray> {
        val builder = HttpRequest.newBuilder(URI.create(env("DOCUMENTS_TEST_CREDIT_URL") + path)).header("Authorization", "Bearer $token")
        if (body != null) {
            builder.header(
                "Idempotency-Key",
                UUID.randomUUID().toString(),
            ).header(
                "Content-Type",
                "application/json",
            ).POST(HttpRequest.BodyPublishers.ofString(body))
        }
        return HttpClient.newHttpClient().send(builder.build(), HttpResponse.BodyHandlers.ofByteArray())
    }
    private fun token(actor: UUID, role: String, company: UUID?): String {
        fun encoded(bytes: ByteArray) = Base64.getUrlEncoder().withoutPadding().encodeToString(bytes)
        val header = encoded("""{"alg":"HS256"}""".toByteArray())
        val payload = encoded(
            mapper.writeValueAsBytes(
                mapOf(
                    "sub" to actor.toString(),
                    "role" to role,
                    "cid" to company?.toString(),
                    "name" to "Проверка",
                    "exp" to Instant.now().plusSeconds(300).epochSecond,
                ),
            ),
        )
        val unsigned = "$header.$payload"
        val key = env("DOCUMENTS_TEST_JWT_SECRET").toByteArray()
        val signature = Mac.getInstance("HmacSHA256").apply { init(SecretKeySpec(key, "HmacSHA256")) }.doFinal(unsigned.toByteArray())
        return "$unsigned.${encoded(signature)}"
    }
    private fun registerArtifactReference(record: ArtifactRecord) {
        db("credit") { connection -> connection.prepareStatement("insert into document_artifact_references(application_id,offer_id,phase,input_hash,input_json) values(?,?,?,?,?::jsonb) on conflict do nothing").use { statement ->
            statement.setObject(1, record.scope.applicationId)
            statement.setObject(2, record.offerId)
            statement.setString(3, record.phase)
            statement.setString(4, record.inputHash)
            statement.setString(5, record.inputJson)
            statement.executeUpdate()
        } }
    }

    private fun db(name: String, action: (java.sql.Connection) -> Unit) {
        DriverManager.getConnection(
            env("DOCUMENTS_TEST_DATABASE_BASE") + "/$name",
            env("DOCUMENTS_TEST_ADMIN_USER"),
            env("DOCUMENTS_TEST_ADMIN_PASSWORD"),
        ).use(action)
    }
    private fun env(name: String): String = System.getenv(name)?.takeIf { it.isNotBlank() } ?: error("Required runtime gate configuration: $name")
}
