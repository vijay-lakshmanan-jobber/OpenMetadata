package org.openmetadata.service.search.security;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;
import static org.openmetadata.service.util.TestUtils.assertFieldDoesNotExist;
import static org.openmetadata.service.util.TestUtils.assertFieldExists;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.jayway.jsonpath.DocumentContext;
import com.jayway.jsonpath.JsonPath;
import es.org.elasticsearch.index.query.QueryBuilder;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.openmetadata.schema.entity.teams.User;
import org.openmetadata.schema.type.EntityReference;
import org.openmetadata.schema.type.MetadataOperation;
import org.openmetadata.service.Entity;
import org.openmetadata.service.search.SearchRepository;
import org.openmetadata.service.search.elasticsearch.queries.ElasticQueryBuilder;
import org.openmetadata.service.search.elasticsearch.queries.ElasticQueryBuilderFactory;
import org.openmetadata.service.search.queries.OMQueryBuilder;
import org.openmetadata.service.search.queries.QueryBuilderFactory;
import org.openmetadata.service.security.policyevaluator.CompiledRule;
import org.openmetadata.service.security.policyevaluator.SubjectContext;

@Execution(ExecutionMode.CONCURRENT)
class ElasticSearchRBACConditionEvaluatorTest {

  private RBACConditionEvaluator evaluator;
  private User mockUser;
  private SubjectContext mockSubjectContext;

  @BeforeEach
  public void setUp() {
    QueryBuilderFactory queryBuilderFactory = new ElasticQueryBuilderFactory();
    evaluator = new RBACConditionEvaluator(queryBuilderFactory);
    SearchRepository mockSearchRepository = mock(SearchRepository.class);
    when(mockSearchRepository.getIndexOrAliasName(anyString()))
        .thenAnswer(
            invocation -> {
              String resource = invocation.getArgument(0);
              return resource.toLowerCase();
            });
    Entity.setSearchRepository(mockSearchRepository);
    mockSubjectContext = mock(SubjectContext.class);
    mockUser = mock(User.class);
    EntityReference mockUserReference = mock(EntityReference.class);
    when(mockUser.getEntityReference()).thenReturn(mockUserReference);
    when(mockUserReference.getId()).thenReturn(UUID.randomUUID());
    when(mockUser.getId()).thenReturn(UUID.randomUUID());
    when(mockUser.getName()).thenReturn("testUser");
    when(mockSubjectContext.user()).thenReturn(mockUser);
  }

  @AfterEach
  public void tearDown() {
    Entity.setSearchRepository(null);
  }

  private void setupMockPolicies(
      List<String> expressions,
      String effect,
      List<List<String>> resourcesList,
      List<List<MetadataOperation>> operationsList) {

    SubjectContext.PolicyContext mockPolicyContext = mock(SubjectContext.PolicyContext.class);
    when(mockPolicyContext.getPolicyName()).thenReturn("TestPolicy");

    List<CompiledRule> mockRules = new ArrayList<>();
    for (int i = 0; i < expressions.size(); i++) {
      CompiledRule mockRule = mock(CompiledRule.class);
      when(mockRule.getOperations()).thenReturn(List.of(MetadataOperation.VIEW_BASIC));
      when(mockRule.getCondition()).thenReturn(expressions.get(i));
      List<String> resources = (resourcesList.size() > i) ? resourcesList.get(i) : List.of("All");
      when(mockRule.getResources()).thenReturn(resources);

      List<MetadataOperation> operations =
          (operationsList.size() > i) ? operationsList.get(i) : List.of(MetadataOperation.VIEW_ALL);
      when(mockRule.getOperations()).thenReturn(operations);

      CompiledRule.Effect mockEffect = CompiledRule.Effect.valueOf(effect.toUpperCase());
      when(mockRule.getEffect()).thenReturn(mockEffect);
      mockRules.add(mockRule);
    }

    when(mockPolicyContext.getRules()).thenReturn(mockRules);
    when(mockSubjectContext.getPolicies(any())).thenReturn(List.of(mockPolicyContext).iterator());
  }

  private void setupMockPolicies(String expression, String effect, List<String> resources) {
    setupMockPolicies(
        List.of(expression),
        effect,
        List.of(resources),
        List.of(List.of(MetadataOperation.VIEW_ALL)));
  }

  private void setupMockPolicies(String expression, String effect) {
    setupMockPolicies(expression, effect, List.of("All"));
  }

  @Test
  void testIsOwner() {
    setupMockPolicies("isOwner()", "ALLOW");

    OMQueryBuilder finalQuery = evaluator.evaluateConditions(mockSubjectContext);
    QueryBuilder elasticQuery = ((ElasticQueryBuilder) finalQuery).build();
    String generatedQuery = elasticQuery.toString();

    assertTrue(generatedQuery.contains("owners.id"), "The query should contain 'owners.id'.");
    assertTrue(
        generatedQuery.contains(mockUser.getId().toString()),
        "The query should contain the user's ID.");
  }

  @Test
  void testNegationWithIsOwner() {
    setupMockPolicies("!isOwner()", "DENY");

    OMQueryBuilder finalQuery = evaluator.evaluateConditions(mockSubjectContext);
    QueryBuilder elasticQuery = ((ElasticQueryBuilder) finalQuery).build();
    String generatedQuery = elasticQuery.toString();

    assertTrue(
        generatedQuery.contains("must_not"), "The query should contain 'must_not' for negation.");
    assertTrue(
        generatedQuery.contains("owners.id"),
        "The query should contain 'owners.id' in the negation.");
    assertTrue(
        generatedQuery.contains(mockUser.getId().toString()),
        "The negation should contain the user's ID.");
  }

  @Test
  void testMatchAnyTag() {
    setupMockPolicies("matchAnyTag('PII.Sensitive', 'PersonalData.Personal')", "ALLOW");

    OMQueryBuilder finalQuery = evaluator.evaluateConditions(mockSubjectContext);
    QueryBuilder elasticQuery = ((ElasticQueryBuilder) finalQuery).build();
    String generatedQuery = elasticQuery.toString();

    assertTrue(generatedQuery.contains("tags.tagFQN"), "The query should contain 'tags.tagFQN'.");
    assertTrue(
        generatedQuery.contains("PII.Sensitive"), "The query should contain 'PII.Sensitive' tag.");
    assertTrue(
        generatedQuery.contains("PersonalData.Personal"),
        "The query should contain 'PersonalData.Personal' tag.");
  }

  @Test
  void testComplexCondition() {
    setupMockPolicies(
        "(matchAnyTag('PII.Sensitive') || matchAllTags('Test.Test1', 'Test.Test2')) && (!isOwner() || noOwner())",
        "ALLOW");

    OMQueryBuilder finalQuery = evaluator.evaluateConditions(mockSubjectContext);
    QueryBuilder elasticQuery = ((ElasticQueryBuilder) finalQuery).build();
    String generatedQuery = elasticQuery.toString();

    assertTrue(generatedQuery.contains("tags.tagFQN"), "The query should contain 'tags.tagFQN'.");
    assertTrue(
        generatedQuery.contains("PII.Sensitive"), "The query should contain 'PII.Sensitive' tag.");
    assertTrue(generatedQuery.contains("Test.Test1"), "The query should contain 'Test.Test1' tag.");
    assertTrue(generatedQuery.contains("Test.Test2"), "The query should contain 'Test.Test2' tag.");
    assertTrue(generatedQuery.contains("owners.id"), "The query should contain 'owners.id'.");

    assertTrue(
        generatedQuery.contains("must_not"), "The query should contain a negation (must_not).");
  }

  @Test
  void testComplexQueryStructure() throws IOException {
    setupMockPolicies(
        "(matchAnyTag('PII.Sensitive') || matchAllTags('Test.Test1', 'Test.Test2')) && (!isOwner() || noOwner())",
        "ALLOW");

    // Evaluate condition and build query
    OMQueryBuilder finalQuery = evaluator.evaluateConditions(mockSubjectContext);
    QueryBuilder elasticQuery = ((ElasticQueryBuilder) finalQuery).build();
    String generatedQuery = elasticQuery.toString();

    // Parse the generated query
    DocumentContext jsonContext = JsonPath.parse(generatedQuery);

    // Check for the presence of the PII.Sensitive tag in the "should" clause
    assertFieldExists(
        jsonContext,
        "$.bool.should[0].bool.should[?(@.term['tags.tagFQN'].value=='PII.Sensitive')]",
        "PII.Sensitive tag");

    // Check for the presence of Test.Test1 and Test.Test2 tags in the "must" clause
    assertFieldExists(
        jsonContext,
        "$.bool.should[1].bool.must[?(@.term['tags.tagFQN'].value=='Test.Test1')]",
        "Test.Test1 tag");
    assertFieldExists(
        jsonContext,
        "$.bool.should[1].bool.must[?(@.term['tags.tagFQN'].value=='Test.Test2')]",
        "Test.Test2 tag");

    // Check for the presence of owner.id in the "must_not" clause for the negation
    assertFieldExists(
        jsonContext,
        "$.bool.should[2].bool.must_not[0].bool.should[?(@.term['owners.id'])]",
        "owners.id in must_not");

    // Check for the presence of must_not for the case where there is no owner
    assertFieldExists(
        jsonContext,
        "$.bool.should[3].bool.must_not[?(@.exists.field=='owners.id')]",
        "no owner must_not clause");

    // Count the number of bool clauses
    ObjectMapper objectMapper = new ObjectMapper();
    JsonNode rootNode = objectMapper.readTree(generatedQuery);
    AtomicInteger boolQueryCount = new AtomicInteger(0);
    countBoolQueries(rootNode, boolQueryCount);
    assertEquals(
        6, boolQueryCount.get(), "There should be no more than 5 'bool' clauses in the query.");
  }

  @Test
  void testHasDomain() {
    setupMockPolicies("hasDomain()", "ALLOW");

    EntityReference domain = new EntityReference();
    domain.setId(UUID.randomUUID());
    domain.setName("Finance");
    when(mockUser.getDomain()).thenReturn(domain);

    OMQueryBuilder finalQuery = evaluator.evaluateConditions(mockSubjectContext);
    QueryBuilder elasticQuery = ((ElasticQueryBuilder) finalQuery).build();
    String generatedQuery = elasticQuery.toString();

    assertTrue(generatedQuery.contains("domain.id"), "The query should contain 'domain.id'.");
    assertTrue(
        generatedQuery.contains(domain.getId().toString()),
        "The query should contain the user's domain ID.");
  }

  @Test
  void testComplexConditionWithRolesDomainTagsTeams() {
    setupMockPolicies(
        "hasAnyRole('Admin', 'DataSteward') && hasDomain() && (matchAnyTag('Sensitive') || inAnyTeam('Interns'))",
        "ALLOW");

    EntityReference role = new EntityReference();
    role.setName("DataSteward");
    when(mockUser.getRoles()).thenReturn(List.of(role));

    EntityReference domain = new EntityReference();
    domain.setId(UUID.randomUUID());
    domain.setName("Finance");
    when(mockUser.getDomain()).thenReturn(domain);

    EntityReference team = new EntityReference();
    team.setId(UUID.randomUUID());
    team.setName("Analytics");
    when(mockUser.getTeams()).thenReturn(List.of(team));

    OMQueryBuilder finalQuery = evaluator.evaluateConditions(mockSubjectContext);
    QueryBuilder elasticQuery = ((ElasticQueryBuilder) finalQuery).build();
    String generatedQuery = elasticQuery.toString();

    DocumentContext jsonContext = JsonPath.parse(generatedQuery);

    // Assert that the query contains 'domain.id'
    assertFieldExists(jsonContext, "$.bool.must[?(@.term['domain.id'])]", "domain.id");

    // Assert that the query contains the user's domain ID
    assertFieldExists(
        jsonContext,
        "$.bool.must[?(@.term['domain.id'].value=='" + domain.getId().toString() + "')]",
        "user's domain ID");

    // Assert that the query contains 'inAnyTeam' logic for 'Analytics'
    assertFieldExists(
        jsonContext, "$.bool.must[?(@.match_all)]", "match_all for inAnyTeam 'Analytics'");

    // Ensure no match_any_tag query is processed since inAnyTeam('Analytics') is true
    assertFieldDoesNotExist(
        jsonContext, "$.bool.should[?(@.term['tags.tagFQN'])]", "matchAnyTag 'Sensitive'");

    // Ensure the query does not contain a match_none condition
    assertFieldDoesNotExist(jsonContext, "$.bool[?(@.match_none)]", "match_none");
  }

  @Test
  void testConditionUserDoesNotHaveRole() {
    setupMockPolicies("hasAnyRole('Admin', 'DataSteward') && isOwner()", "ALLOW");
    EntityReference role = new EntityReference();
    role.setName("DataConsumer");
    when(mockUser.getRoles()).thenReturn(List.of(role));
    OMQueryBuilder finalQuery = evaluator.evaluateConditions(mockSubjectContext);
    QueryBuilder elasticQuery = ((ElasticQueryBuilder) finalQuery).build();
    String generatedQuery = elasticQuery.toString();

    // Adjust the assertion
    assertTrue(
        generatedQuery.contains("\"must_not\""), "The query should contain 'must_not' clause.");
    assertTrue(
        generatedQuery.contains("\"match_all\""),
        "The must_not clause should contain 'match_all' query.");
  }

  @Test
  void testNegationWithRolesAndTeams() {
    setupMockPolicies("!(hasAnyRole('Viewer') || inAnyTeam('Marketing')) && isOwner()", "ALLOW");
    EntityReference role = new EntityReference();
    role.setName("Viewer");
    when(mockUser.getRoles()).thenReturn(List.of(role));

    EntityReference team = new EntityReference();
    team.setId(UUID.randomUUID());
    team.setName("Marketing");
    when(mockUser.getTeams()).thenReturn(List.of(team));

    OMQueryBuilder finalQuery = evaluator.evaluateConditions(mockSubjectContext);
    QueryBuilder elasticQuery = ((ElasticQueryBuilder) finalQuery).build();
    String generatedQuery = elasticQuery.toString();
    assertTrue(generatedQuery.contains("must_not"), "The query should contain 'must_not'.");
    assertTrue(
        generatedQuery.contains("match_all"), "The must_not clause should contain 'match_all'.");
  }

  @Test
  void testComplexConditionUserMeetsAllCriteria() {
    setupMockPolicies(
        "hasDomain() && inAnyTeam('Engineering', 'Analytics') && matchAllTags('Confidential', 'Internal')",
        "ALLOW");

    EntityReference domain = new EntityReference();
    domain.setId(UUID.randomUUID());
    domain.setName("Technology");
    when(mockUser.getDomain()).thenReturn(domain);

    EntityReference team = new EntityReference();
    team.setId(UUID.randomUUID());
    team.setName("Engineering");
    when(mockUser.getTeams()).thenReturn(List.of(team));

    OMQueryBuilder finalQuery = evaluator.evaluateConditions(mockSubjectContext);
    QueryBuilder elasticQuery = ((ElasticQueryBuilder) finalQuery).build();
    String generatedQuery = elasticQuery.toString();

    assertTrue(generatedQuery.contains("domain.id"), "The query should contain 'domain.id'.");
    assertTrue(
        generatedQuery.contains(domain.getId().toString()),
        "The query should contain the user's domain ID.");
    assertTrue(generatedQuery.contains("tags.tagFQN"), "The query should contain 'tags.tagFQN'.");
    assertTrue(
        generatedQuery.contains("Confidential"), "The query should contain 'Confidential' tag.");
    assertTrue(generatedQuery.contains("Internal"), "The query should contain 'Internal' tag.");
    assertFalse(generatedQuery.contains("match_none"), "The query should not be match_none.");
  }

  @Test
  void testConditionUserLacksDomain() {
    setupMockPolicies("hasDomain() && isOwner() && matchAnyTag('Public')", "ALLOW");
    when(mockUser.getDomain()).thenReturn(null);
    OMQueryBuilder finalQuery = evaluator.evaluateConditions(mockSubjectContext);
    QueryBuilder elasticQuery = ((ElasticQueryBuilder) finalQuery).build();
    String generatedQuery = elasticQuery.toString();
    DocumentContext jsonContext = JsonPath.parse(generatedQuery);
    assertFieldExists(
        jsonContext, "$.bool.must_not[?(@.exists.field=='domain.id')]", "must_not for domain.id");

    // Check for owner ID and Public tag in the query
    assertFieldExists(
        jsonContext,
        "$.bool.should[?(@.term['owners.id'].value=='" + mockUser.getId().toString() + "')]",
        "owner.id");
    assertFieldExists(
        jsonContext, "$.bool.should[?(@.term['tags.tagFQN'].value=='Public')]", "Public tag");
  }

  @Test
  void testNestedLogicalOperators() {
    setupMockPolicies(
        "(hasAnyRole('Admin') || inAnyTeam('Engineering')) && (matchAnyTag('Sensitive') || isOwner())",
        "ALLOW");

    EntityReference role = new EntityReference();
    role.setName("Admin");
    when(mockUser.getRoles()).thenReturn(List.of(role));

    EntityReference team = new EntityReference();
    team.setId(UUID.randomUUID());
    team.setName("Marketing");
    when(mockUser.getTeams()).thenReturn(List.of(team));

    OMQueryBuilder finalQuery = evaluator.evaluateConditions(mockSubjectContext);
    QueryBuilder elasticQuery = ((ElasticQueryBuilder) finalQuery).build();
    String generatedQuery = elasticQuery.toString();

    assertTrue(generatedQuery.contains("tags.tagFQN"), "The query should contain 'tags.tagFQN'.");
    assertTrue(generatedQuery.contains("Sensitive"), "The query should contain 'Sensitive' tag.");
    assertTrue(generatedQuery.contains("owners.id"), "The query should contain 'owners.id'.");
    assertFalse(generatedQuery.contains("match_none"), "The query should not be match_none.");
  }

  private void countBoolQueries(JsonNode node, AtomicInteger count) {
    if (node.isObject()) {
      if (node.has("bool")) {
        count.incrementAndGet();
        countBoolQueries(node.get("bool"), count);
      } else {
        node.fields()
            .forEachRemaining(
                entry -> {
                  countBoolQueries(entry.getValue(), count);
                });
      }
    } else if (node.isArray()) {
      node.forEach(
          element -> {
            countBoolQueries(element, count);
          });
    }
  }

  @Test
  void testComplexNestedConditions() {
    setupMockPolicies(
        "(hasAnyRole('Admin') || (matchAnyTag('Confidential') && hasDomain())) && (!inAnyTeam('HR') || noOwner())",
        "ALLOW");

    EntityReference role = new EntityReference();
    role.setName("Admin");
    when(mockUser.getRoles()).thenReturn(List.of(role));

    EntityReference domain = new EntityReference();
    domain.setId(UUID.randomUUID());
    when(mockUser.getDomain()).thenReturn(domain);

    EntityReference team = new EntityReference();
    team.setId(UUID.randomUUID());
    team.setName("Engineering");
    when(mockUser.getTeams()).thenReturn(List.of(team));

    OMQueryBuilder finalQuery = evaluator.evaluateConditions(mockSubjectContext);
    QueryBuilder elasticQuery = ((ElasticQueryBuilder) finalQuery).build();
    String generatedQuery = elasticQuery.toString();

    DocumentContext jsonContext = JsonPath.parse(generatedQuery);

    // Assertions
    assertFieldExists(
        jsonContext, "$.bool.must[?(@.match_all)]", "match_all for hasAnyRole 'Admin'");

    // Ensure no further processing for matchAnyTag('Confidential') or hasDomain()
    assertFieldDoesNotExist(
        jsonContext, "$.bool.must[?(@.term['tags.tagFQN'])]", "matchAnyTag 'Confidential'");
    assertFieldDoesNotExist(
        jsonContext, "$.bool.must[?(@.term['domain.id'])]", "hasDomain 'domain.id'");

    // Ensure that the query does not check for noOwner since inAnyTeam('HR') is false
    assertFieldDoesNotExist(
        jsonContext, "$.bool.must_not[?(@.exists.field=='owners.id')]", "noOwner clause");

    // Ensure the query does not contain a match_none condition
    assertFieldDoesNotExist(jsonContext, "$.bool[?(@.match_none)]", "match_none");
  }

  @Test
  void testMultipleNestedNotOperators() {
    setupMockPolicies(
        "!(matchAllTags('Sensitive', 'Internal') && (!hasDomain()) || isOwner())", "ALLOW");

    when(mockUser.getDomain()).thenReturn(null); // User has no domain
    when(mockUser.getId()).thenReturn(UUID.randomUUID());

    OMQueryBuilder finalQuery = evaluator.evaluateConditions(mockSubjectContext);
    QueryBuilder elasticQuery = ((ElasticQueryBuilder) finalQuery).build();
    String generatedQuery = elasticQuery.toString();
    DocumentContext jsonContext = JsonPath.parse(generatedQuery);

    assertFieldExists(
        jsonContext,
        "$.bool.must_not[0].bool.should[0].bool.must[?(@.term['tags.tagFQN'].value=='Sensitive')]",
        "Sensitive");
    assertFieldExists(
        jsonContext,
        "$.bool.must_not[0].bool.should[0].bool.must[?(@.term['tags.tagFQN'].value=='Internal')]",
        "Internal");
    assertFieldExists(
        jsonContext,
        "$.bool.must_not[0].bool.should[1].bool.should[?(@.term['owners.id'])]",
        "owners.id");
  }

  @Test
  void testComplexOrConditionsWithNegations() {
    setupMockPolicies(
        "(hasAnyRole('Analyst') && matchAnyTag('Public')) || (!hasAnyRole('Viewer') && !inAnyTeam('Finance'))",
        "ALLOW");

    // Mock user roles
    EntityReference role = new EntityReference();
    role.setName("DataScientist");
    when(mockUser.getRoles()).thenReturn(List.of(role));

    // Mock user teams
    EntityReference team = new EntityReference();
    team.setId(UUID.randomUUID());
    team.setName("Marketing");
    when(mockUser.getTeams()).thenReturn(List.of(team));

    // Evaluate condition
    OMQueryBuilder finalQuery = evaluator.evaluateConditions(mockSubjectContext);
    QueryBuilder elasticQuery = ((ElasticQueryBuilder) finalQuery).build();
    String generatedQuery = elasticQuery.toString();

    // The user does not have the 'Analyst' role and is not in 'Finance' team.
    // The condition '!hasAnyRole('Viewer') && !inAnyTeam('Finance')' evaluates to true.
    // The overall condition is true, so the query should be match_all or have no must_not clauses.
    assertFalse(
        generatedQuery.contains("\"must_not\""),
        "The query should not contain 'must_not' clause for roles or teams.");
  }

  @Test
  void testNestedAndOrWithMultipleMethods() {
    setupMockPolicies(
        "(hasDomain() || matchAnyTag('External')) && (inAnyTeam('Engineering', 'Analytics') || hasAnyRole('DataEngineer')) && !noOwner()",
        "ALLOW");

    // Mock user domain
    EntityReference domain = new EntityReference();
    domain.setId(UUID.randomUUID());
    when(mockUser.getDomain()).thenReturn(domain);

    // Mock user teams
    EntityReference team1 = new EntityReference();
    team1.setId(UUID.randomUUID());
    team1.setName("Engineering");
    EntityReference team2 = new EntityReference();
    team2.setId(UUID.randomUUID());
    team2.setName("Marketing");
    when(mockUser.getTeams()).thenReturn(List.of(team1, team2));

    // Mock user roles
    EntityReference role = new EntityReference();
    role.setName("DataScientist");
    when(mockUser.getRoles()).thenReturn(List.of(role));

    // Evaluate condition
    OMQueryBuilder finalQuery = evaluator.evaluateConditions(mockSubjectContext);
    QueryBuilder elasticQuery = ((ElasticQueryBuilder) finalQuery).build();
    String generatedQuery = elasticQuery.toString();

    // Assertions
    assertTrue(
        generatedQuery.contains("\"domain.id\""), "The query should contain 'domain.id' term.");
    assertTrue(
        generatedQuery.contains("\"owners.id\""), "The query should contain 'owners.id' term.");
    assertFalse(
        generatedQuery.contains("\"must_not\" : [ { \"exists\""),
        "The query should not contain 'must_not' clause for 'noOwner()'.");
  }

  @Test
  void testComplexConditionWithAllMethods() {
    setupMockPolicies(
        "(hasAnyRole('Admin') && hasDomain() && matchAllTags('PII', 'Sensitive')) || (isOwner() && !matchAnyTag('Restricted'))",
        "ALLOW");

    // Mock user roles
    EntityReference role = new EntityReference();
    role.setName("Admin");
    when(mockUser.getRoles()).thenReturn(List.of(role));

    // Mock user domain
    EntityReference domain = new EntityReference();
    domain.setId(UUID.randomUUID());
    when(mockUser.getDomain()).thenReturn(domain);

    // Mock user ownership
    when(mockUser.getId()).thenReturn(UUID.randomUUID());
    EntityReference userRef = new EntityReference();
    userRef.setId(mockUser.getId());
    userRef.setType("user");
    when(mockUser.getEntityReference()).thenReturn(userRef);

    // Evaluate condition and build query
    OMQueryBuilder finalQuery = evaluator.evaluateConditions(mockSubjectContext);
    QueryBuilder elasticQuery = ((ElasticQueryBuilder) finalQuery).build();
    String generatedQuery = elasticQuery.toString();

    // Parse the generated query
    DocumentContext jsonContext = JsonPath.parse(generatedQuery);

    // Assertions

    // Check for the presence of domain.id
    assertFieldExists(
        jsonContext, "$.bool.should[0].bool.must[?(@.term['domain.id'])]", "domain.id");

    // Check for the presence of PII and Sensitive tags in the matchAllTags clause
    assertFieldExists(
        jsonContext,
        "$.bool.should[0].bool.must[?(@.term['tags.tagFQN'].value=='PII')]",
        "PII tag");
    assertFieldExists(
        jsonContext,
        "$.bool.should[0].bool.must[?(@.term['tags.tagFQN'].value=='Sensitive')]",
        "Sensitive tag");

    // Check for the presence of must_not for matchAnyTag('Restricted')
    assertFieldExists(
        jsonContext,
        "$.bool.should[1].bool.must_not[0].bool.should[?(@.term['tags.tagFQN'].value=='Restricted')]",
        "must_not for matchAnyTag 'Restricted'");

    // Check for the presence of owner.id in the second should block
    assertFieldExists(
        jsonContext, "$.bool.should[1].bool.should[?(@.term['owners.id'])]", "owners.id");
  }

  @Test
  void testMultipleOrConditionsWithNestedAnd() {
    setupMockPolicies(
        "(hasAnyRole('Admin') || hasAnyRole('DataSteward')) && (matchAnyTag('Finance') || matchAllTags('Confidential', 'Internal')) && !inAnyTeam('Data')",
        "ALLOW");

    // Mock user roles
    EntityReference role = new EntityReference();
    role.setName("DataSteward");
    when(mockUser.getRoles()).thenReturn(List.of(role));

    // Mock user teams
    EntityReference team = new EntityReference();
    team.setId(UUID.randomUUID());
    team.setName("Engineering");
    when(mockUser.getTeams()).thenReturn(List.of(team));

    // Evaluate condition
    OMQueryBuilder finalQuery = evaluator.evaluateConditions(mockSubjectContext);
    QueryBuilder elasticQuery = ((ElasticQueryBuilder) finalQuery).build();
    String generatedQuery = elasticQuery.toString();
    DocumentContext jsonContext = JsonPath.parse(generatedQuery);
    // Assertions
    // Assert that the `hasAnyRole` check results in `match_all` since the user has 'DataSteward'
    // role
    // Assert that the `hasAnyRole` check results in `match_all`
    assertFieldExists(jsonContext, "$.bool.must[?(@.match_all)]", "match_all for hasAnyRole");

    // Assert that the query contains tag conditions for matchAnyTag('Finance') and
    // matchAllTags('Confidential', 'Internal')
    assertFieldExists(
        jsonContext,
        "$.bool.should[0].bool.should[?(@.term['tags.tagFQN'].value=='Finance')]",
        "Finance tag");

    assertFieldExists(
        jsonContext,
        "$.bool.should[1].bool.must[?(@.term['tags.tagFQN'].value=='Confidential')]",
        "Confidential tag");

    assertFieldExists(
        jsonContext,
        "$.bool.should[1].bool.must[?(@.term['tags.tagFQN'].value=='Internal')]",
        "Internal tag");

    // Ensure no must_not for inAnyTeam('Data') since the user is in 'Engineering'
    assertFieldDoesNotExist(jsonContext, "$.bool.must_not", "must_not for inAnyTeam('Data')");
  }

  @Test
  void testComplexConditionWithMultipleNegations() {
    setupMockPolicies(
        "!((hasAnyRole('Admin') || inAnyTeam('Engineering')) && matchAnyTag('Confidential') && matchAllTags('Sensitive', 'Classified')) && hasDomain() && isOwner()",
        "ALLOW");

    // Mock user roles
    EntityReference role = new EntityReference();
    role.setName("Admin");
    when(mockUser.getRoles()).thenReturn(List.of(role));

    // Mock user teams
    EntityReference team = new EntityReference();
    team.setId(UUID.randomUUID());
    team.setName("Engineering");
    when(mockUser.getTeams()).thenReturn(List.of(team));

    // Mock user domain
    EntityReference domain = new EntityReference();
    domain.setId(UUID.randomUUID());
    when(mockUser.getDomain()).thenReturn(domain);

    // Mock user ownership
    when(mockUser.getId()).thenReturn(UUID.randomUUID());
    EntityReference userRef = new EntityReference();
    userRef.setId(mockUser.getId());
    userRef.setType("user");
    when(mockUser.getEntityReference()).thenReturn(userRef);

    // Evaluate condition
    OMQueryBuilder finalQuery = evaluator.evaluateConditions(mockSubjectContext);
    QueryBuilder elasticQuery = ((ElasticQueryBuilder) finalQuery).build();
    String generatedQuery = elasticQuery.toString();
    DocumentContext jsonContext = JsonPath.parse(generatedQuery);
    // `domain.id` should be in `must`
    assertFieldExists(jsonContext, "$.bool.must[?(@.term['domain.id'])]", "domain.id");

    // `Sensitive` and `Classified` tags should be in `must_not[0].bool.must`
    assertFieldExists(
        jsonContext,
        "$.bool.must_not[0].bool.must[?(@.term['tags.tagFQN'].value=='Sensitive')]",
        "Sensitive");
    assertFieldExists(
        jsonContext,
        "$.bool.must_not[0].bool.must[?(@.term['tags.tagFQN'].value=='Classified')]",
        "Classified");

    // `Confidential` tag should be in `must_not[0].bool.should`
    assertFieldExists(
        jsonContext,
        "$.bool.must_not[0].bool.should[?(@.term['tags.tagFQN'].value=='Confidential')]",
        "Confidential");

    // Ownership (isOwner condition) should be in `should`
    assertFieldExists(jsonContext, "$.bool.should[?(@.term['owners.id'])]", "owners.id");
  }

  @Test
  void testNotHasDomainWhenUserHasNoDomain() {
    setupMockPolicies("!hasDomain() && isOwner()", "ALLOW");
    when(mockUser.getDomain()).thenReturn(null);
    when(mockUser.getId()).thenReturn(UUID.randomUUID());

    OMQueryBuilder finalQuery = evaluator.evaluateConditions(mockSubjectContext);
    QueryBuilder elasticQuery = ((ElasticQueryBuilder) finalQuery).build();
    String generatedQuery = elasticQuery.toString();

    DocumentContext jsonContext = JsonPath.parse(generatedQuery);

    assertFieldExists(
        jsonContext,
        "$.bool.must_not[0].bool.must_not[?(@.exists.field=='domain.id')]",
        "must_not for hasDomain");
    assertFieldExists(
        jsonContext,
        "$.bool.should[?(@.term['owners.id'].value=='" + mockUser.getId().toString() + "')]",
        "owners.id");
    assertFieldDoesNotExist(jsonContext, "$.bool[?(@.match_none)]", "match_none should not exist");
  }

  @Test
  void testIndexFilteringBasedOnResource() {
    // Assume the rule applies to 'Table' resource
    setupMockPolicies("hasAnyRole('Admin') && matchAnyTag('Sensitive')", "ALLOW", List.of("Table"));

    // Mock user roles
    EntityReference role = new EntityReference();
    role.setName("Admin");
    when(mockUser.getRoles()).thenReturn(List.of(role));

    // Evaluate condition and build query
    OMQueryBuilder finalQuery = evaluator.evaluateConditions(mockSubjectContext);
    QueryBuilder elasticQuery = ((ElasticQueryBuilder) finalQuery).build();
    String generatedQuery = elasticQuery.toString();

    DocumentContext jsonContext = JsonPath.parse(generatedQuery);

    // Assert that the query contains the appropriate index (e.g., 'table_search_index')
    assertFieldExists(
        jsonContext,
        "$.bool.must[?(@.terms._index && @.terms._index[?(@ == 'table')])]",
        "Index filtering for 'Table' resource");
    // Assert that the query contains 'tags.tagFQN' for 'Sensitive' tag
    assertFieldExists(
        jsonContext, "$.bool.should[?(@.term['tags.tagFQN'].value=='Sensitive')]", "Sensitive tag");
  }

  @Test
  void testComplexConditionWithIndexFiltering() {
    // Assume the rule applies to 'Database' and 'Table' resources
    setupMockPolicies(
        "hasDomain() && matchAnyTag('Sensitive')", "ALLOW", List.of("Database", "Table"));

    EntityReference domain = new EntityReference();
    domain.setId(UUID.randomUUID());
    domain.setName("Technology");
    when(mockUser.getDomain()).thenReturn(domain);

    OMQueryBuilder finalQuery = evaluator.evaluateConditions(mockSubjectContext);
    QueryBuilder elasticQuery = ((ElasticQueryBuilder) finalQuery).build();
    String generatedQuery = elasticQuery.toString();

    DocumentContext jsonContext = JsonPath.parse(generatedQuery);

    // Assert that the query contains the appropriate indices for 'Database' and 'Table'
    assertFieldExists(
        jsonContext,
        "$.bool.must[?(@.terms._index && @.terms._index[?(@ == 'database')])]",
        "Index filtering for 'Database' resource");
    assertFieldExists(
        jsonContext,
        "$.bool.must[?(@.terms._index && @.terms._index[?(@ == 'table')])]",
        "Index filtering for 'Table' resource");

    // Assert that the query contains 'tags.tagFQN' for 'Sensitive' tag
    assertFieldExists(
        jsonContext, "$.bool.should[?(@.term['tags.tagFQN'].value=='Sensitive')]", "Sensitive tag");
    assertFieldExists(
        jsonContext,
        "$.bool.must[?(@.term['domain.id'].value=='" + domain.getId().toString() + "')]",
        "domain.id");
  }

  @Test
  void testMultipleRulesInPolicy() {
    // Set up policies with multiple rules
    setupMockPolicies(
        List.of(
            "hasAnyRole('Admin') && matchAnyTag('Sensitive')",
            "inAnyTeam('Engineering') && matchAllTags('Confidential', 'Internal')"),
        "ALLOW",
        List.of(List.of("Table"), List.of("Dashboard")),
        List.of(List.of(MetadataOperation.VIEW_BASIC)));

    // Mock user roles and teams
    EntityReference role = new EntityReference();
    role.setName("Admin");
    when(mockUser.getRoles()).thenReturn(List.of(role));

    EntityReference team = new EntityReference();
    team.setId(UUID.randomUUID());
    team.setName("Engineering");
    when(mockUser.getTeams()).thenReturn(List.of(team));

    // Evaluate conditions
    OMQueryBuilder finalQuery = evaluator.evaluateConditions(mockSubjectContext);
    QueryBuilder elasticQuery = ((ElasticQueryBuilder) finalQuery).build();
    String generatedQuery = elasticQuery.toString();

    // Assertions
    DocumentContext jsonContext = JsonPath.parse(generatedQuery);

    // Check for the "Table" index condition
    assertFieldExists(
        jsonContext,
        "$.bool.should[0].bool.must[?(@.terms._index && @.terms._index[?(@ == 'table')])]",
        "Index filtering for 'Table' resource");
    assertFieldExists(
        jsonContext,
        "$.bool.should[0].bool.must[?(@.match_all)]",
        "match_all for hasAnyRole 'Admin'");
    assertFieldExists(
        jsonContext,
        "$.bool.should[0].bool.should[?(@.term['tags.tagFQN'].value=='Sensitive')]",
        "Sensitive tag");

    // Check for the "Dashboard" index condition
    assertFieldExists(
        jsonContext,
        "$.bool.should[1].bool.must[?(@.terms._index && @.terms._index[?(@ == 'database')])]",
        "Index filtering for 'Database' resource");
    assertFieldExists(
        jsonContext,
        "$.bool.should[1].bool.must[?(@.match_all)]",
        "match_all for inAnyTeam 'Engineering'");
    assertFieldExists(
        jsonContext,
        "$.bool.should[1].bool.must[?(@.term['tags.tagFQN'].value=='Confidential')]",
        "Confidential tag");
    assertFieldExists(
        jsonContext,
        "$.bool.should[1].bool.must[?(@.term['tags.tagFQN'].value=='Internal')]",
        "Internal tag");
  }

  @Test
  void testMultiplePoliciesInRole() {
    // Mock multiple policies in a single role
    setupMockPolicies(
        List.of("hasDomain() && matchAnyTag('Public')", "!inAnyTeam('HR') || isOwner()"),
        "ALLOW",
        List.of(List.of("Table"), List.of("Dashboard")),
        List.of(List.of(MetadataOperation.VIEW_BASIC)));

    // Mock user roles
    EntityReference role = new EntityReference();
    role.setName("DataSteward");
    when(mockUser.getRoles()).thenReturn(List.of(role));

    // Mock user teams
    EntityReference team = new EntityReference();
    team.setId(UUID.randomUUID());
    team.setName("Finance");
    when(mockUser.getTeams()).thenReturn(List.of(team));

    // Mock user domain
    EntityReference domain = new EntityReference();
    domain.setId(UUID.randomUUID());
    when(mockUser.getDomain()).thenReturn(domain);

    // Mock user ownership
    when(mockUser.getId()).thenReturn(UUID.randomUUID());

    // Evaluate the condition
    OMQueryBuilder finalQuery = evaluator.evaluateConditions(mockSubjectContext);
    QueryBuilder elasticQuery = ((ElasticQueryBuilder) finalQuery).build();
    String generatedQuery = elasticQuery.toString();
    DocumentContext jsonContext = JsonPath.parse(generatedQuery);

    // Assertions
    // Check for domain filtering in the "Table" index clause
    assertFieldExists(
        jsonContext,
        "$.bool.should[0].bool.must[?(@.term['domain.id'].value=='"
            + domain.getId().toString()
            + "')]",
        "user's domain ID");

    // Check for the matchAnyTag clause for Public tag in the "Table" index clause
    assertFieldExists(
        jsonContext,
        "$.bool.should[0].bool.should[?(@.term['tags.tagFQN'].value=='Public')]",
        "Public tag");
  }

  @Test
  void testRoleAndPolicyInheritanceFromTeams() {
    // Mock policies inherited through team hierarchy
    setupMockPolicies(
        List.of(
            "hasAnyRole('Manager') && hasDomain()",
            "inAnyTeam('Engineering') && matchAnyTag('Critical')"),
        "ALLOW",
        List.of(List.of("All"), List.of("All")),
        List.of(List.of(MetadataOperation.VIEW_BASIC)));

    // Mock user teams with inherited roles
    EntityReference team = new EntityReference();
    team.setId(UUID.randomUUID());
    team.setName("Engineering");
    when(mockUser.getTeams()).thenReturn(List.of(team));

    EntityReference inheritedRole = new EntityReference();
    inheritedRole.setName("Manager");
    when(mockUser.getRoles())
        .thenReturn(List.of(inheritedRole)); // User inherits the 'Manager' role

    // Mock user domain
    EntityReference domain = new EntityReference();
    domain.setId(UUID.randomUUID());
    domain.setName("Operations");
    when(mockUser.getDomain()).thenReturn(domain);

    // Evaluate the condition
    OMQueryBuilder finalQuery = evaluator.evaluateConditions(mockSubjectContext);
    QueryBuilder elasticQuery = ((ElasticQueryBuilder) finalQuery).build();
    String generatedQuery = elasticQuery.toString();
    DocumentContext jsonContext = JsonPath.parse(generatedQuery);

    // Assertions
    // Adjust the assertion for the hasDomain clause
    assertFieldExists(
        jsonContext,
        "$.bool.should[0].bool.must[?(@.term['domain.id'].value=='"
            + domain.getId().toString()
            + "')]",
        "user's domain ID");

    // Check for the inAnyTeam('Engineering') clause
    assertFieldExists(
        jsonContext,
        "$.bool.should[1].bool.must[?(@.match_all)]",
        "match_all for inAnyTeam 'Engineering'");

    // Check for the matchAnyTag clause for Critical tag
    assertFieldExists(
        jsonContext,
        "$.bool.should[1].bool.should[?(@.term['tags.tagFQN'].value=='Critical')]",
        "Critical tag");
  }

  @Test
  void testRuleWithNonViewOperationIgnored() {
    // Rule with operation EditDescription, which should be ignored in search RBAC
    setupMockPolicies(
        List.of("isOwner()"),
        "ALLOW",
        List.of(List.of("All")),
        List.of(List.of(MetadataOperation.EDIT_DESCRIPTION)));

    // Mock user ownership
    UUID userId = UUID.randomUUID();
    when(mockUser.getId()).thenReturn(userId);

    // Evaluate the condition
    OMQueryBuilder finalQuery = evaluator.evaluateConditions(mockSubjectContext);
    QueryBuilder elasticQuery = ((ElasticQueryBuilder) finalQuery).build();
    String generatedQuery = elasticQuery.toString();
    assertTrue(generatedQuery.contains("match_all"), "The query should contain 'match_all'");
  }

  @Test
  void testRuleWithViewBasicOperationApplied() {
    // Rule with operation ViewBasic, which should affect search results
    setupMockPolicies(
        List.of("isOwner()"),
        "ALLOW",
        List.of(List.of("All")),
        List.of(List.of(MetadataOperation.VIEW_BASIC)));

    // Mock user ownership
    UUID userId = UUID.randomUUID();
    when(mockUser.getId()).thenReturn(userId);

    // Evaluate the condition
    OMQueryBuilder finalQuery = evaluator.evaluateConditions(mockSubjectContext);
    QueryBuilder elasticQuery = ((ElasticQueryBuilder) finalQuery).build();
    String generatedQuery = elasticQuery.toString();

    // The rule should affect the search query
    assertTrue(generatedQuery.contains("owners.id"), "The query should contain 'owner.id'");
    assertTrue(
        generatedQuery.contains(userId.toString()), "The query should contain the user's ID");
  }

  @Test
  void testDenyAllOperationsOnTableResource() {
    setupMockPolicies(
        List.of(""), "DENY", List.of(List.of("Table")), List.of(List.of(MetadataOperation.ALL)));

    UUID userId = UUID.randomUUID();
    when(mockUser.getId()).thenReturn(userId);

    OMQueryBuilder finalQuery = evaluator.evaluateConditions(mockSubjectContext);
    QueryBuilder elasticQuery = ((ElasticQueryBuilder) finalQuery).build();
    String generatedQuery = elasticQuery.toString();
    DocumentContext jsonContext = JsonPath.parse(generatedQuery);

    assertFieldExists(
        jsonContext,
        "$.bool.must_not[0].bool.must[?(@.terms._index && @.terms._index[?(@ == 'table')])]",
        "must_not clause excluding 'table'");

    // Assertions to ensure 'table' is not included in 'must' or 'should' clauses
    assertFieldDoesNotExist(
        jsonContext,
        "$.bool.must[?(@.terms._index && @.terms._index[?(@ == 'table')])]",
        "'table' should not be included in must clauses");
    assertFieldDoesNotExist(
        jsonContext,
        "$.bool.should[?(@.terms._index && @.terms._index[?(@ == 'table')])]",
        "'table' should not be included in should clauses");
  }

  @Test
  void testUserSeesOwnedAndUnownedResourcesIncludingTeamOwnership() {
    // Set up a policy with three rules
    setupMockPolicies(
        List.of(
            "noOwner()", // Rule 1 condition (ViewBasic)
            "isOwner()" // Rule 2 condition (All operations)
            ),
        "ALLOW",
        List.of(
            List.of("All"), // Rule 1 resources
            List.of("All") // Rule 2 resources
            ),
        List.of(List.of(MetadataOperation.EDIT_OWNERS), List.of(MetadataOperation.ALL)));

    UUID userId = UUID.randomUUID();
    when(mockUser.getId()).thenReturn(userId);

    UUID teamId1 = UUID.randomUUID();
    UUID teamId2 = UUID.randomUUID();
    EntityReference team1 = new EntityReference();
    team1.setId(teamId1);
    team1.setName("TeamA");

    EntityReference team2 = new EntityReference();
    team2.setId(teamId2);
    team2.setName("TeamB");

    when(mockUser.getTeams()).thenReturn(List.of(team1, team2));

    OMQueryBuilder finalQuery = evaluator.evaluateConditions(mockSubjectContext);
    QueryBuilder elasticQuery = ((ElasticQueryBuilder) finalQuery).build();
    String generatedQuery = elasticQuery.toString();
    DocumentContext jsonContext = JsonPath.parse(generatedQuery);

    assertFieldExists(
        jsonContext,
        "$.bool.should[?(@.term['owners.id'].value=='" + userId + "')]",
        "The query should allow resources where the user is the owner");

    assertFieldExists(
        jsonContext,
        "$.bool.should[?(@.term['owners.id'].value=='" + teamId1 + "')]",
        "The query should allow resources where TeamA is the owner");
    assertFieldExists(
        jsonContext,
        "$.bool.should[?(@.term['owners.id'].value=='" + teamId2 + "')]",
        "The query should allow resources where TeamB is the owner");
  }

  @Test
  void testPoliciesProcessing() {
    List<CompiledRule> compiledRules = new ArrayList<>();
    List<Map<String, Object>> policies = getPolicies();

    for (Map<String, Object> policy : policies) {
      CompiledRule rule = createCompiledRule(policy);
      compiledRules.add(rule);
    }

    SubjectContext.PolicyContext mockPolicyContext = mock(SubjectContext.PolicyContext.class);
    when(mockPolicyContext.getRules()).thenReturn(compiledRules);
    Iterator<SubjectContext.PolicyContext> policyContextIterator =
        Collections.singletonList(mockPolicyContext).iterator();
    when(mockSubjectContext.getPolicies(anyList())).thenReturn(policyContextIterator);

    OMQueryBuilder finalQuery = evaluator.evaluateConditions(mockSubjectContext);

    QueryBuilder elasticQuery = ((ElasticQueryBuilder) finalQuery).build();
    String generatedQuery = elasticQuery.toString();
    DocumentContext jsonContext = JsonPath.parse(generatedQuery);

    assertFalse(
        generatedQuery.contains("Create"), "The query should not contain 'Create' operation");
    assertFalse(
        generatedQuery.contains("Delete"), "The query should not contain 'Delete' operation");
    assertFalse(generatedQuery.contains("Edit"), "The query should not contain 'Edit' operation");

    boolean containsOwnerCondition = generatedQuery.contains("owners.id");
    boolean containsTagCondition = generatedQuery.contains("tags.tagFQN");
    assertTrue(
        containsOwnerCondition || containsTagCondition,
        "The query should include 'isOwner' or 'matchAnyTag' conditions where applicable");
  }

  // Helper methods
  private CompiledRule createCompiledRule(Map<String, Object> policyDef) {
    CompiledRule rule = mock(CompiledRule.class);
    when(rule.getName()).thenReturn((String) policyDef.get("name"));
    when(rule.getDescription()).thenReturn((String) policyDef.get("description"));
    when(rule.getEffect())
        .thenReturn(CompiledRule.Effect.valueOf(((String) policyDef.get("effect")).toUpperCase()));
    when(rule.getOperations())
        .thenReturn(mapOperations((List<String>) policyDef.get("operations")));
    when(rule.getResources()).thenReturn((List<String>) policyDef.get("resources"));
    when(rule.getCondition()).thenReturn((String) policyDef.getOrDefault("condition", ""));
    return rule;
  }

  private List<MetadataOperation> mapOperations(List<String> operations) {
    List<MetadataOperation> mappedOperations = new ArrayList<>();
    for (String op : operations) {
      if (op.equalsIgnoreCase("Create")
          || op.equalsIgnoreCase("Delete")
          || op.toLowerCase().startsWith("edit")) {
        mappedOperations.add(MetadataOperation.VIEW_BASIC);
      } else if (op.equalsIgnoreCase("All")) {
        mappedOperations.add(MetadataOperation.ALL);
      } else {
        mappedOperations.add(MetadataOperation.fromValue(op));
      }
    }
    return mappedOperations;
  }

  private List<Map<String, Object>> getPolicies() {
    List<Map<String, Object>> policyDefs = new ArrayList<>();

    Map<String, Object> policy1 = new HashMap<>();
    policy1.put("name", "APP_ALLOW");
    policy1.put("description", "APP MARKET PLACE DEFINITION ALLOW AS OWNER");
    policy1.put("effect", "allow");
    policy1.put("operations", List.of("ViewAll"));
    policy1.put("resources", List.of("appMarketPlaceDefinition"));
    policy1.put("condition", "matchAnyTag('tags.a') && isOwner()");
    policyDefs.add(policy1);

    Map<String, Object> policy2 = new HashMap<>();
    policy2.put("name", "APP_ALLOW_2");
    policy2.put("description", "APP MARKET PLACE DEFINITION ALLOW");
    policy2.put("effect", "allow");
    policy2.put("operations", List.of("ViewBasic"));
    policy2.put("resources", List.of("appMarketPlaceDefinition"));
    policyDefs.add(policy2);

    Map<String, Object> policy3 = new HashMap<>();
    policy3.put("name", "APP_DENY");
    policy3.put("description", "APP MARKET PLACE DEFINITION DENY");
    policy3.put("effect", "deny");
    policy3.put("operations", List.of("Create", "Delete", "EditAll"));
    policy3.put("resources", List.of("appMarketPlaceDefinition"));
    policyDefs.add(policy3);

    Map<String, Object> policy4 = new HashMap<>();
    policy4.put("name", "APP_ALLOW_2");
    policy4.put("description", "APP ALLOW");
    policy4.put("effect", "allow");
    policy4.put("operations", List.of("ViewBasic"));
    policy4.put("resources", List.of("app"));
    policyDefs.add(policy4);

    Map<String, Object> policy5 = new HashMap<>();
    policy5.put("name", "APP_ALLOW_3");
    policy5.put("description", "APP DENY");
    policy5.put("effect", "deny");
    policy5.put("operations", List.of("Create", "Delete", "EditAll"));
    policy5.put("resources", List.of("app"));
    policyDefs.add(policy5);

    return policyDefs;
  }
}
