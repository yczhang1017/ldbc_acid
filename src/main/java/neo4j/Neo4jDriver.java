package neo4j;

import com.google.common.collect.ImmutableMap;
import driver.TestDriver;
import groovy.transform.Immutable;
import org.neo4j.driver.v1.AuthTokens;
import org.neo4j.driver.v1.Config;
import org.neo4j.driver.v1.Driver;
import org.neo4j.driver.v1.GraphDatabase;
import org.neo4j.driver.v1.Record;
import org.neo4j.driver.v1.StatementResult;
import org.neo4j.driver.v1.Transaction;
import org.neo4j.driver.v1.Value;

import java.util.List;
import java.util.Map;
import java.util.Random;

public class Neo4jDriver extends TestDriver<Transaction, Map<String, Object>, StatementResult> {

    protected Driver driver;

    public Neo4jDriver(int port) {
        driver = GraphDatabase.driver("bolt://localhost:" + port, AuthTokens.none());
    }

    @Override
    public void close() throws Exception {
        driver.close();
    }

    @Override
    public Transaction startTransaction() {
        return driver.session().beginTransaction();
    }

    @Override
    public void commitTransaction(Transaction tt) {
        tt.success();
        tt.close();
    }

    @Override
    public void abortTransaction(Transaction tt) {
        tt.failure();
        tt.close();
    }

    @Override
    public StatementResult runQuery(Transaction tt, String querySpecification, Map<String, Object> queryParameters) {
        return tt.run(querySpecification, queryParameters);
    }

    @Override
    public void nukeDatabase() {
        final Transaction tt = startTransaction();
        tt.run("MATCH (n) DETACH DELETE n");
        commitTransaction(tt);
    }

    @Override
    public void atomicityInit() {
        final Transaction tt = startTransaction();
        tt.run("CREATE (:Person {id: 1, name: 'Alice', emails: ['alice@aol.com']}),\n" +
                " (:Person {id: 2, name: 'Bob', emails: ['bob@hotmail.com', 'bobby@yahoo.com']})");
        commitTransaction(tt);
    }

    @Override
    public void atomicityC(Map<String, Object> parameters) {
        final Transaction tt = startTransaction();

        tt.run("MATCH (p1:Person {id: $person1Id})\n" +
                "CREATE (p2:Person)\n" +
                "CREATE (p1)-[k:KNOWS]->(p2)\n" +
                "SET\n" +
                "  p1.emails = p1.emails + [$newEmail],\n" +
                "  p2.id = $person2Id,\n" +
                "  k.since = $since", parameters);
        commitTransaction(tt);
    }

    @Override
    public void atomicityRB(Map<String, Object> parameters) {
        final Transaction tt = startTransaction();

        tt.run("MATCH (p1:Person {id: $person1Id}) SET p1.emails = p1.emails + [$newEmail]", parameters);
        final StatementResult result = tt.run("MATCH (p2:Person {id: $person2Id}) RETURN p2", parameters);
        if (result.hasNext()) {
            abortTransaction(tt);
        } else {
            tt.run("CREATE (p2:Person {id: $person2Id, emails: []})", parameters);
            commitTransaction(tt);
        }
    }

    @Override
    public Map<String, Object> atomicityCheck() {
        final Transaction tt = startTransaction();

        StatementResult result = tt.run("MATCH (p:Person)\n" +
                "RETURN count(p) AS numPersons, count(p.name) AS numNames, sum(size(p.emails)) AS numEmails");
        Record record = result.next();
        final long numPersons = record.get("numPersons").asLong();
        final long numNames = record.get("numNames").asLong();
        final long numEmails = record.get("numEmails").asLong();

        return ImmutableMap.of("numPersons", numPersons, "numNames", numNames, "numEmails", numEmails);
    }

    @Override
    public void g0Init() {
        final Transaction tt = startTransaction();
        tt.run("CREATE (:Person {id: 1, versionHistory: [0]})-[:KNOWS {versionHistory: [0]}]->(:Person {id: 2, versionHistory: [0]})");
        commitTransaction(tt);
    }

    @Override
    public Map<String, Object> g0(Map<String, Object> parameters) {
        final Transaction tt = startTransaction();

        tt.run("MATCH (p1:Person {id: $person1Id})-[k:KNOWS]->(p2:Person {id: $person2Id})\n" +
                "SET p1.versionHistory = p1.versionHistory + [$transactionId]\n" +
                "SET p2.versionHistory = p2.versionHistory + [$transactionId]\n" +
                "SET k.versionHistory  = k.versionHistory  + [$transactionId]", parameters);
        commitTransaction(tt);

        return ImmutableMap.of();
    }

    @Override
    public Map<String, Object> g0check(Map<String, Object> parameters) {
        final Transaction tt = startTransaction();

        StatementResult result = tt.run("MATCH (p1:Person {id: $person1Id})-[k:KNOWS]->(p2:Person {id: $person2Id})\n" +
                "RETURN\n" +
                "  p1.versionHistory AS p1VersionHistory,\n" +
                "  k.versionHistory  AS kVersionHistory,\n" +
                "  p2.versionHistory AS p2VersionHistory", parameters);
        Record record = result.next();
        final List<Object> p1VersionHistory = record.get("p1VersionHistory").asList();
        final List<Object> kVersionHistory  = record.get("kVersionHistory" ).asList();
        final List<Object> p2VersionHistory = record.get("p2VersionHistory").asList();

        return ImmutableMap.of("p1VersionHistory", p1VersionHistory, "kVersionHistory", kVersionHistory, "p2VersionHistory", p2VersionHistory);
    }

    @Override
    public void g1aInit() {
        final Transaction tt = startTransaction();
        tt.run("CREATE (:Person {id: 1, version: 1})");
        commitTransaction(tt);
    }

    @Override
    public Map<String, Object> g1a1(Map<String, Object> parameters) {
        final Transaction tt = startTransaction();

        // we cannot pass p as a parameter so we pass its internal ID instead
        final StatementResult result = tt.run("MATCH (p:Person {id: $personId})\n" +
                "RETURN ID(p) AS internalPId", parameters);
        if (!result.hasNext()) throw new IllegalStateException("G1a1 StatementResult empty");
        final Value internalPId = result.next().get("internalPId");

        sleep((Long) parameters.get("sleepTime"));

        tt.run("MATCH (p:Person)\n" +
                "WHERE ID(p) = $internalPId\n" +
                "SET p.version = 2", ImmutableMap.of("internalPId", internalPId));

        sleep((Long) parameters.get("sleepTime"));

        abortTransaction(tt);
        return ImmutableMap.of();
    }

    @Override
    public Map<String, Object> g1a2(Map<String, Object> parameters) {
        final Transaction tt = startTransaction();

        final StatementResult result = tt.run("MATCH (p:Person {id: $personId}) RETURN p.version AS pVersion", parameters);
        if (!result.hasNext()) throw new IllegalStateException("G1a T2 StatementResult empty");
        final long pVersion = result.next().get("pVersion").asLong();

        return ImmutableMap.of("pVersion", pVersion);
    }

    @Override
    public void g1bInit() {
        final Transaction tt = startTransaction();
        tt.run("CREATE (:Person {id: 1, version: 99})");
        commitTransaction(tt);
    }

    @Override
    public Map<String, Object> g1b1(Map<String, Object> parameters) {
        final Transaction tt = startTransaction();

        tt.run("MATCH (p:Person {id: $personId}) SET p.version = $even", parameters);
        sleep((Long) parameters.get("sleepTime"));
        tt.run("MATCH (p:Person {id: $personId}) SET p.version = $odd", parameters);

        commitTransaction(tt);
        return ImmutableMap.of();
    }

    @Override
    public Map<String, Object> g1b2(Map<String, Object> parameters) {
        final Transaction tt = startTransaction();

        final StatementResult result = tt.run("MATCH (p:Person {id: $personId}) RETURN p.version AS pVersion", parameters);
        if (!result.hasNext()) throw new IllegalStateException("G1b T2 StatementResult empty");
        final long pVersion = result.next().get("pVersion").asLong();

        return ImmutableMap.of("pVersion", pVersion);
    }

    @Override
    public void g1cInit() {
        final Transaction tt = startTransaction();
        tt.run("CREATE (:Person {id: 1, version: 0}), (:Person {id: 2, version: 0})");
        commitTransaction(tt);
    }

    @Override
    public Map<String, Object> g1c(Map<String, Object> parameters) {
        final Transaction tt = startTransaction();

        StatementResult result = tt.run("MATCH (p1:Person {id: $person1Id})\n" +
                "SET p1.version = $transactionId\n" +
                "WITH count(*) AS dummy\n" +
                "MATCH (p2:Person {id: $person2Id})\n" +
                "RETURN p2.version AS person2Version\n", parameters);
        final long person2Version = result.next().get("person2Version").asLong();
        commitTransaction(tt);

        return ImmutableMap.of("person2Version", person2Version);
    }

    // IMP

    @Override
    public void impInit() {
        final Transaction tt = startTransaction();
        tt.run("CREATE (:Person {id: 1, version: 1})");
        commitTransaction(tt);
    }

    @Override
    public Map<String, Object> imp1(Map<String, Object> parameters) {
        final Transaction tt = startTransaction();
        tt.run("MATCH (p:Person {id: $personId}) SET p.version = p.version + 1 RETURN p", parameters);
        commitTransaction(tt);
        return ImmutableMap.of();
    }

    @Override
    public Map<String, Object> imp2(Map<String, Object> parameters) {
        final Transaction tt = startTransaction();

        final StatementResult result1 = tt.run("MATCH (p:Person {id: $personId}) RETURN p.version AS firstRead", parameters);
        if (!result1.hasNext()) throw new IllegalStateException("IMP result1 empty");
        final long firstRead = result1.next().get("firstRead").asLong();

        sleep((Long) parameters.get("sleepTime"));

        final StatementResult result2 = tt.run("MATCH (p:Person {id: $personId}) RETURN p.version AS secondRead", parameters);
        if (!result2.hasNext()) throw new IllegalStateException("IMP result2 empty");
        final long secondRead = result2.next().get("secondRead").asLong();

        return ImmutableMap.of("firstRead", firstRead, "secondRead", secondRead);
    }

    // PMP

    @Override
    public void pmpInit() {
        final Transaction tt = startTransaction();
        tt.run("CREATE (:Person {id: 1}), (:Post {id: 1})");
        commitTransaction(tt);
    }

    @Override
    public Map<String, Object> pmp1(Map<String, Object> parameters) {
        final Transaction tt = startTransaction();
        tt.run("MATCH (pe:Person {id: $personId}), (po:Post {id: $postId}) CREATE (pe)-[:LIKES]->(po)", parameters);
        commitTransaction(tt);
        return ImmutableMap.of();
    }

    @Override
    public Map<String, Object> pmp2(Map<String, Object> parameters) {
        final Transaction tt = startTransaction();

        final StatementResult result1 = tt.run("MATCH (po1:Post {id: $postId})<-[:LIKES]-(pe1:Person) RETURN count(pe1) AS firstRead", parameters);
        if (!result1.hasNext()) throw new IllegalStateException("PMP result1 empty");
        final long firstRead = result1.next().get("firstRead").asLong();

        sleep((Long) parameters.get("sleepTime"));

        final StatementResult result2 = tt.run("MATCH (po2:Post {id: $postId})<-[:LIKES]-(pe2:Person) RETURN count(pe2) AS secondRead", parameters);
        if (!result2.hasNext()) throw new IllegalStateException("PMP result2 empty");
        final long secondRead = result2.next().get("secondRead").asLong();

        return ImmutableMap.of("firstRead", firstRead, "secondRead", secondRead);
    }

    @Override
    public void otvInit() {
        final Transaction tt = startTransaction();
        tt.run("CREATE (p1:Person {id: 1, version: 0})-[:KNOWS]->" +
                "  (:Person {id: 2, version: 0})-[:KNOWS]->" +
                "  (:Person {id: 3, version: 0})-[:KNOWS]->" +
                "  (:Person {id: 4, version: 0})-[:KNOWS]->(p1)");
        commitTransaction(tt);
    }

    @Override
    public Map<String, Object> otv1(Map<String, Object> parameters) {
        Random random = new Random();
        for (int i = 0; i < 100; i++) {
            long personId  = random.nextInt((int) parameters.get("cycleSize")+1);

            final Transaction tt = startTransaction();
            tt.run("MATCH path = (p1:Person {id: $personId})-[:KNOWS]->(p2)-[:KNOWS]->(p3)-[:KNOWS]->(p4)-[:KNOWS]->(p1)\n" +
                            " SET p1.version = p1.version + 1\n" +
                            " SET p2.version = p2.version + 1\n" +
                            " SET p3.version = p3.version + 1\n" +
                            " SET p4.version = p4.version + 1\n",
                    ImmutableMap.of("personId", personId));
            commitTransaction(tt);
            // TODO: once memgraph fixes this, use an actual path
        }
        return ImmutableMap.of();
    }

    @Override
    public Map<String, Object> otv2(Map<String, Object> parameters) {
        final Transaction tt = startTransaction();
        final StatementResult result1 = tt.run("MATCH (p1:Person {id: $personId})-[:KNOWS]->(p2)-[:KNOWS]->(p3)-[:KNOWS]->(p4)-[:KNOWS]->(p1) RETURN [p1.version, p2.version, p3.version, p4.version] AS firstRead", parameters);
        if (!result1.hasNext()) throw new IllegalStateException("OTV2 result1 empty");
        final List<Object> firstRead = result1.next().get("firstRead").asList();

        sleep((Long) parameters.get("sleepTime"));

        final StatementResult result2 = tt.run("MATCH (p1:Person {id: $personId})-[:KNOWS]->(p2)-[:KNOWS]->(p3)-[:KNOWS]->(p4)-[:KNOWS]->(p1) RETURN [p1.version, p2.version, p3.version, p4.version] AS secondRead", parameters);
        if (!result2.hasNext()) throw new IllegalStateException("OTV2 result2 empty");
        final List<Object> secondRead = result2.next().get("secondRead").asList();

        return ImmutableMap.of("firstRead", firstRead, "secondRead", secondRead);
    }

    @Override
    public void frInit() {
        final Transaction tt = startTransaction();
        tt.run("CREATE (p1:Person {id: 1, version: 0})-[:KNOWS]->" +
                "  (:Person {id: 2, version: 0})-[:KNOWS]->" +
                "  (:Person {id: 3, version: 0})-[:KNOWS]->" +
                "  (:Person {id: 4, version: 0})-[:KNOWS]->(p1)");
        commitTransaction(tt);
    }

    @Override
    public Map<String, Object> fr1(Map<String, Object> parameters) {
        final Transaction tt = startTransaction();
        tt.run("MATCH path = (n:Person {id: $personId})-[:KNOWS*..4]->(n)\n" +
                " UNWIND nodes(path) AS person\n" +
                " WITH DISTINCT person\n" +
                " SET person.version = person.version + 1", parameters);
        commitTransaction(tt);

        return ImmutableMap.of();
    }

    @Override
    public Map<String, Object> fr2(Map<String, Object> parameters) {
        final Transaction tt = startTransaction();

        final StatementResult result1 = tt.run("MATCH path1 = (n1:Person {id: $personId})-[:KNOWS*..4]->(n1) RETURN extract(p IN nodes(path1) | p.version) AS firstRead", parameters);
        if (!result1.hasNext()) throw new IllegalStateException("FR2 result1 empty");
        final List<Object> firstRead = result1.next().get("firstRead").asList();

        sleep((Long) parameters.get("sleepTime"));

        final StatementResult result2 = tt.run("MATCH path1 = (n1:Person {id: $personId})-[:KNOWS*..4]->(n1) RETURN extract(p IN nodes(path1) | p.version) AS secondRead", parameters);
        if (!result2.hasNext()) throw new IllegalStateException("FR2 result2 empty");
        final List<Object> secondRead = result2.next().get("secondRead").asList();

        return ImmutableMap.of("firstRead", firstRead, "secondRead", secondRead);
    }

    // LU

    @Override
    public void luInit() {
        final Transaction tt = startTransaction();
        tt.run("CREATE (:Person {id: 1, numFriends: 0})");
        commitTransaction(tt);
    }

    @Override
    public Map<String, Object> lu1(Map<String, Object> parameters) {
        final Transaction tt = startTransaction();
        tt.run("MATCH (p1:Person {id: 1})\n" +
                "SET p1.numFriends = p1.numFriends + 1\n" +
                "RETURN p1.numFriends\n");
        commitTransaction(tt);
        return ImmutableMap.of();
    }

    @Override
    public Map<String, Object> lu2(Map<String, Object> parameters) {
        final Transaction tt = startTransaction();
        final StatementResult result = tt.run("MATCH (p:Person {id: $personId})\n" +
                "OPTIONAL MATCH (p)-[k:KNOWS]->()\n" +
                "WITH p, count(k) AS numKnowsEdges\n" +
                "RETURN numKnowsEdges,\n" +
                "       p.numFriends AS numFriendsProp\n",parameters);
        final Record record = result.next();
        long numKnowsEdges = record.get("numKnowsEdges").asLong();
        long numFriends = record.get("numFriendsProp").asLong();
        return ImmutableMap.of("numKnowsEdges", numKnowsEdges, "numFriendsProp", numFriends);

    }

    @Override
    public void wsInit() {
        final Transaction tt = startTransaction();
        tt.run("CREATE (:Person {id: 1})");
        tt.run("CREATE (:Person {id: 2})");
        tt.run("CREATE (:Person {id: 3})");
        tt.run("CREATE (:Person {id: 4})");

        tt.run("CREATE (:Forum {id: 1})");
        tt.run("CREATE (:Forum {id: 2})");
        tt.run("CREATE (:Forum {id: 3})");
        commitTransaction(tt);
    }

    @Override
    public Map<String, Object> ws1(Map<String, Object> parameters) {
        final Transaction tt = startTransaction();

        System.out.println("tId " + parameters.get("transactionId") + ", forumId " + parameters.get("forumId"));
        final StatementResult result = tt.run("MATCH (f:Forum {id: $forumId})-[:HAS_MODERATOR]->(p:Person {id: $personId})\n" +
                "RETURN f, p", parameters);

        if (!result.hasNext()) {

            System.out.println("tId " + parameters.get("transactionId") + ", forumId " + parameters.get("forumId") + " SELECTED FOR CHANGE");
            final StatementResult result2 = tt.run("MATCH (f:Forum)-[h:HAS_MODERATOR]->(:Person)\n" +
                    "WITH f, count(h) AS modCount\n" +
                    "RETURN\n" +
                    "  f.id AS forumId,\n" +
                    "  modCount");
            while (result2.hasNext()) System.out.println("tId " + parameters.get("transactionId") + ", forumId " + parameters.get("forumId") + "   " + result2.next());
//            System.out.print("XXXXXX ");

            sleep((Long) parameters.get("sleepTime"));
            tt.run("MATCH (f:Forum {id: $forumId}),\n" +
                    "  (p:Person {id: $personId})\n" +
                    "CREATE (f)-[:HAS_MODERATOR]->(p)", parameters);
            commitTransaction(tt);
            System.out.println("tId " + parameters.get("transactionId") + ", forumId " + parameters.get("forumId") + "   MADE IT");

        }

        return ImmutableMap.of();
    }

    @Override
    public Map<String, Object> ws2(Map<String, Object> parameters) {
        final Transaction tt = startTransaction();

        final StatementResult result = tt.run("MATCH (f:Forum)-[h:HAS_MODERATOR]->(:Person)\n" +
                "WITH f, count(h) AS modCount\n" +
                "WHERE modCount > 1\n" +
                "RETURN\n" +
                "  f.id AS forumId,\n" +
                "  modCount");

        if (result.hasNext()) {
            final Record record = result.next();
            final long forumId = record.get("forumId").asLong();
            final long modCount = record.get("modCount").asLong();
            return ImmutableMap.of("forumId", forumId, "modCount", modCount);
        } else {
            return ImmutableMap.of();
        }
    }

}
