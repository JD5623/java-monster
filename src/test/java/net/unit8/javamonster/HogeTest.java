package net.unit8.javamonster;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import graphql.ExecutionResult;
import graphql.GraphQL;
import graphql.Scalars;
import graphql.relay.Relay;
import graphql.schema.*;
import net.unit8.javamonster.jdbc.StandardJdbcDatabaseCallback;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static graphql.schema.FieldCoordinates.coordinates;
import static graphql.schema.GraphQLArgument.newArgument;
import static graphql.schema.GraphQLFieldDefinition.newFieldDefinition;
import static net.unit8.javamonster.ValueUtils.intValue;

public class HogeTest {
    private static final Logger LOG = LoggerFactory.getLogger(HogeTest.class);
    private ObjectMapper mapper = new ObjectMapper();

    private DataSource dataSource;

    private HikariDataSource createDataSource() {
        HikariConfig hikariConfig = new HikariConfig();
        hikariConfig.setJdbcUrl("jdbc:h2:mem:test;DATABASE_TO_LOWER=TRUE");
        hikariConfig.setUsername("sa");
        return new HikariDataSource(hikariConfig);
    }

    @BeforeEach
    void setup() throws SQLException {
        dataSource = createDataSource();
        final Flyway flyway = Flyway.configure().dataSource(dataSource).load();
        flyway.migrate();

        try(Connection conn = dataSource.getConnection();
            PreparedStatement stmt = conn.prepareStatement("SHOW TABLES");
            final ResultSet rs = stmt.executeQuery()) {
            while(rs.next()) {
                LOG.debug("CREATED TABLE={}", rs.getString(1));
            }
        }
    }
    public GraphQL createSchema() {
        DatabaseCallback<List<Map<String, Object>>> dbCall = new StandardJdbcDatabaseCallback(dataSource);
        GraphQLObjectType userAddressType = GraphQLObjectType.newObject()
                .name("UserAddress")
                .field(newFieldDefinition()
                        .name("address")
                        .type(Scalars.GraphQLString)
                )
                .build();
        GraphQLObjectType userType = GraphQLObjectType.newObject()
                .name("User")
                .field(newFieldDefinition()
                        .name("name")
                        .type(Scalars.GraphQLString)
                )
                .field(newFieldDefinition()
                        .name("user_addresses")
                        .type(GraphQLList.list(userAddressType))
                .argument(newArgument().name("gid").type(Scalars.GraphQLString)))
                .build();
        GraphQLObjectType userConnectionType = new Relay().connectionType("User",
                new Relay().edgeType("User", userType,
                        null,
                        java.util.Arrays.asList()),
                java.util.Arrays.asList(
                        // TODO Add total field.
                ));
        GraphQLObjectType query = GraphQLObjectType.newObject()
                .name("Query")
                .field(newFieldDefinition()
                        .name("users")
                        .argument(newArgument()
                                .name("first")
                                .type(Scalars.GraphQLBigInteger)
                                .build())
                        .type(userConnectionType)
                )
                .field(newFieldDefinition()
                        .name("friend")
                        .argument(newArgument()
                                .name("id")
                                .type(Scalars.GraphQLBigInteger)
                                .build())
                        .type(userType))
                .build();
        TypeResolver typeResolver = env -> null;

        GraphQLCodeRegistry codeRegistry = GraphQLCodeRegistry.newCodeRegistry()
                .dataFetcher(coordinates("Query", "users"),
                        new JavaMonsterResolver.Builder<>()
                                .sqlTable("user_table")
                                .sqlPaginate(true)
                                .orderBy(new OrderColumn("id"))
                                .dbCall(dbCall)
                                .build())
                .dataFetcher(coordinates("Query", "friend"),
                        new JavaMonsterResolver.Builder<>()
                                .sqlTable("user_table")
                                .where((table, args, context) -> {
                                    Optional<Integer> maybeId = intValue(args.get("id"));
                                    return maybeId.map(id ->
                                            table + ".id = " + id
                                    ).orElse(null);
                                })
                                .dbCall(dbCall)
                                .build())
                .dataFetcher(coordinates("User", "user_addresses"),
                        new JavaMonsterResolver.Builder<>()
                                .sqlTable("user_address_table")
                                .sqlJoin((userTable, userAddressTable, args, context) ->
                                        userTable + ".id=" + userAddressTable + ".user_id"
                                )
                                .build())
                .build();
        GraphQLSchema schema = GraphQLSchema.newSchema()
                .query(query)
                .additionalType(Relay.pageInfoType)
                .codeRegistry(codeRegistry)
                .build();

        return GraphQL.newGraphQL(schema).build();
    }

    @Test
    void relayQuery() throws JsonProcessingException {
        String graphQuery = "query { users(first:5) { edges { cursor node { name user_addresses { address }}}}}";
        LOG.info("GraphQL Query={}", graphQuery);
        GraphQL graphql = createSchema();
        ExecutionResult result = graphql.execute(graphQuery);
        if (!result.getErrors().isEmpty()) {
            LOG.warn("{}", result.getErrors());
        } else {
            LOG.info(mapper.writeValueAsString(result.toSpecification()));
        }
    }

    @Test
    void fragment() throws JsonProcessingException {
        String graphQuery = "query { "
                + " friend1: friend(id:1) { ...friendFields } "
                + " friend2: friend(id:2) { ...friendFields } "
                + " friend3: friend(id:3) { ...friendFields } "
                + "} fragment friendFields on User { name user_addresses { address }} ";
        LOG.info("GraphQL Query={}", graphQuery);
        GraphQL graphql = createSchema();
        ExecutionResult result = graphql.execute(graphQuery);
        if (!result.getErrors().isEmpty()) {
            LOG.warn("{}", result.getErrors());
        } else {
            LOG.info(mapper.writeValueAsString(result.toSpecification()));
        }
    }

}
