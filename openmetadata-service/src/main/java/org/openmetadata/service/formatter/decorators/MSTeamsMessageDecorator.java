/*
 *  Copyright 2021 Collate
 *  Licensed under the Apache License, Version 2.0 (the "License");
 *  you may not use this file except in compliance with the License.
 *  You may obtain a copy of the License at
 *  http://www.apache.org/licenses/LICENSE-2.0
 *  Unless required by applicable law or agreed to in writing, software
 *  distributed under the License is distributed on an "AS IS" BASIS,
 *  WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *  See the License for the specific language governing permissions and
 *  limitations under the License.
 */

package org.openmetadata.service.formatter.decorators;

import static org.openmetadata.common.utils.CommonUtil.nullOrEmpty;
import static org.openmetadata.service.util.email.EmailUtil.getSmtpSettings;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Map;
import org.openmetadata.schema.type.ChangeEvent;
import org.openmetadata.service.apps.bundles.changeEvent.msteams.TeamsMessage;
import org.openmetadata.service.apps.bundles.changeEvent.msteams.TeamsMessage.AdaptiveCardContent;
import org.openmetadata.service.apps.bundles.changeEvent.msteams.TeamsMessage.Attachment;
import org.openmetadata.service.apps.bundles.changeEvent.msteams.TeamsMessage.Column;
import org.openmetadata.service.apps.bundles.changeEvent.msteams.TeamsMessage.ColumnSet;
import org.openmetadata.service.apps.bundles.changeEvent.msteams.TeamsMessage.Image;
import org.openmetadata.service.apps.bundles.changeEvent.msteams.TeamsMessage.TextBlock;
import org.openmetadata.service.exception.UnhandledServerException;

public class MSTeamsMessageDecorator implements MessageDecorator<TeamsMessage> {

  @Override
  public String getBold() {
    return "**%s**";
  }

  @Override
  public String getBoldWithSpace() {
    return "**%s** ";
  }

  @Override
  public String getLineBreak() {
    return " <br/> ";
  }

  @Override
  public String getAddMarker() {
    return "**";
  }

  @Override
  public String getAddMarkerClose() {
    return "** ";
  }

  @Override
  public String getRemoveMarker() {
    return "~~";
  }

  @Override
  public String getRemoveMarkerClose() {
    return "~~ ";
  }

  @Override
  public String getEntityUrl(String prefix, String fqn, String additionalParams) {
    return String.format(
        "[%s](/%s/%s%s)",
        fqn.trim(),
        getSmtpSettings().getOpenMetadataUrl(),
        prefix,
        nullOrEmpty(additionalParams) ? "" : String.format("/%s", additionalParams));
  }

  @Override
  public TeamsMessage buildEntityMessage(String publisherName, ChangeEvent event) {
    return getTeamMessage(publisherName, event, createEntityMessage(publisherName, event));
  }

  @Override
  public TeamsMessage buildTestMessage(String publisherName) {
    return getTeamTestMessage(publisherName);
  }

  public TeamsMessage getTeamTestMessage(String publisherName) {
    if (publisherName.isEmpty()) {
      throw new UnhandledServerException("Publisher name not found.");
    }

    return createConnectionTestMessage(publisherName);
  }

  @Override
  public TeamsMessage buildThreadMessage(String publisherName, ChangeEvent event) {
    OutgoingMessage threadMessage = createThreadMessage(publisherName, event);
    return createGeneralChangeEventMessage(publisherName, event, threadMessage);
  }

  private TeamsMessage getTeamMessage(
      String publisherName, ChangeEvent event, OutgoingMessage outgoingMessage) {
    if (outgoingMessage.getMessages().isEmpty()) {
      throw new UnhandledServerException("No messages found for the event");
    }

    //    return createGeneralChangeEventMessage(publisherName, event, outgoingMessage);

    return createDQMessage(publisherName, event, outgoingMessage);
  }

  private TeamsMessage createGeneralChangeEventMessage(
      String publisherName, ChangeEvent event, OutgoingMessage outgoingMessage) {

    Map<General_Template_Section, Map<Enum<?>, Object>> templateData =
        buildGeneralTemplateData(publisherName, event, outgoingMessage);

    Map<Enum<?>, Object> eventDetails = templateData.get(General_Template_Section.EVENT_DETAILS);

    TextBlock changeEventDetailsTextBlock = createHeader();

    // Create the facts for the FactSet
    List<TeamsMessage.Fact> facts = createEventDetailsFacts(eventDetails);

    // Create a list of TextBlocks for each message with a separator
    List<TextBlock> messageTextBlocks =
        outgoingMessage.getMessages().stream()
            .map(
                message ->
                    TextBlock.builder()
                        .type("TextBlock")
                        .text(message)
                        .wrap(true)
                        .spacing("Medium")
                        .separator(true)
                        .build())
            .toList();

    TextBlock footerMessage = createFooterMessage();

    ColumnSet columnSet =
        ColumnSet.builder()
            .type("ColumnSet")
            .columns(
                List.of(
                    Column.builder()
                        .type("Column")
                        .items(List.of(createOMImageMessage()))
                        .width("auto")
                        .build(),
                    Column.builder()
                        .type("Column")
                        .items(List.of(changeEventDetailsTextBlock))
                        .width("stretch")
                        .build()))
            .build();

    // Create the body list and combine all elements
    List<TeamsMessage.BodyItem> body = new ArrayList<>();
    body.add(columnSet);
    body.add(TeamsMessage.FactSet.builder().type("FactSet").facts(facts).build());
    body.addAll(messageTextBlocks); // Add the containers with message TextBlocks
    body.add(footerMessage);

    Attachment attachment =
        Attachment.builder()
            .contentType("application/vnd.microsoft.card.adaptive")
            .content(
                AdaptiveCardContent.builder()
                    .type("AdaptiveCard")
                    .version("1.0")
                    .body(body) // Pass the combined body list
                    .build())
            .build();

    return TeamsMessage.builder().type("message").attachments(List.of(attachment)).build();
  }

  private TeamsMessage createDQMessage(
      String publisherName, ChangeEvent event, OutgoingMessage outgoingMessage) {

    // todo - complete buildDQTemplateData fn
    Map<DQ_Template_Section, Map<Enum<?>, Object>> dqTemplateData =
        buildDQTemplateData(publisherName, event, outgoingMessage);

    TextBlock changeEventDetailsTextBlock = createHeader();

    Map<Enum<?>, Object> eventDetails = dqTemplateData.get(DQ_Template_Section.EVENT_DETAILS);

    // Create the facts for different sections
    List<TeamsMessage.Fact> facts = createEventDetailsFacts(eventDetails);
    List<TeamsMessage.Fact> testCaseDetailsFacts = createTestCaseDetailsFacts(dqTemplateData);
    List<TeamsMessage.Fact> testCaseResultFacts = createTestCaseResultFacts(dqTemplateData);
    List<TeamsMessage.Fact> inspectionQueryFacts = createInspectionQueryFacts(dqTemplateData);
    List<TeamsMessage.Fact> testDefinitionFacts = createTestDefinitionFacts(dqTemplateData);
    List<TeamsMessage.Fact> sampleDataFacts = createSampleDataFacts(dqTemplateData);

    // Create a list of TextBlocks for each message with a separator
    List<TextBlock> messageTextBlocks =
        outgoingMessage.getMessages().stream()
            .map(
                message ->
                    TextBlock.builder()
                        .type("TextBlock")
                        .text(message)
                        .wrap(true)
                        .spacing("Medium")
                        .separator(true) // Set separator for each message
                        .build())
            .toList();

    TextBlock footerMessage = createFooterMessage();

    ColumnSet columnSet =
        ColumnSet.builder()
            .type("ColumnSet")
            .columns(
                List.of(
                    Column.builder()
                        .type("Column")
                        .items(List.of(createOMImageMessage()))
                        .width("auto")
                        .build(),
                    Column.builder()
                        .type("Column")
                        .items(List.of(changeEventDetailsTextBlock))
                        .width("stretch")
                        .build()))
            .build();

    // Divider between sections
    TextBlock divider = createDivider();

    // Create the body list and combine all elements with dividers between fact sets
    List<TeamsMessage.BodyItem> body = new ArrayList<>();
    body.add(columnSet);

    // event details facts
    body.add(TeamsMessage.FactSet.builder().type("FactSet").facts(facts).build());

    // test case details facts
    if (dqTemplateData.containsKey(DQ_Template_Section.TEST_CASE_DETAILS)) {
      body.add(createBoldTextBlock("Test Case Details"));
      body.add(TeamsMessage.FactSet.builder().type("FactSet").facts(testCaseDetailsFacts).build());
      body.add(divider);
    }

    // test case result facts
    if (dqTemplateData.containsKey(DQ_Template_Section.TEST_CASE_RESULT)) {
      body.add(createBoldTextBlock("Test Case Result"));
      body.add(TeamsMessage.FactSet.builder().type("FactSet").facts(testCaseResultFacts).build());
      body.add(divider);
    }

    // inspection query facts
    if (dqTemplateData.containsKey(DQ_Template_Section.TEST_CASE_DETAILS)) {
      body.add(TeamsMessage.FactSet.builder().type("FactSet").facts(inspectionQueryFacts).build());
      body.add(divider);
    }

    // test definition facts
    if (dqTemplateData.containsKey(DQ_Template_Section.TEST_DEFINITION)) {
      body.add(createBoldTextBlock("Test Definition"));
      body.add(TeamsMessage.FactSet.builder().type("FactSet").facts(testDefinitionFacts).build());
      body.add(divider);
    }

    // Add sample data facts
    if (dqTemplateData.containsKey(DQ_Template_Section.TEST_CASE_DETAILS)) {
      body.add(TeamsMessage.FactSet.builder().type("FactSet").facts(sampleDataFacts).build());
    }

    // Add the outgoing message text blocks
    body.addAll(messageTextBlocks);

    body.add(footerMessage);

    // Create the attachment with the combined body list
    Attachment attachment =
        Attachment.builder()
            .contentType("application/vnd.microsoft.card.adaptive")
            .content(
                AdaptiveCardContent.builder()
                    .type("AdaptiveCard")
                    .version("1.0")
                    .body(body) // Pass the combined body list
                    .build())
            .build();

    return TeamsMessage.builder().type("message").attachments(List.of(attachment)).build();
  }

  private List<TeamsMessage.Fact> createEventDetailsFacts(Map<Enum<?>, Object> detailsMap) {
    return List.of(
        createFact("Event Type:", String.valueOf(detailsMap.get(EventDetailsKeys.EVENT_TYPE))),
        createFact("Updated By:", String.valueOf(detailsMap.get(EventDetailsKeys.UPDATED_BY))),
        createFact("Entity Type:", String.valueOf(detailsMap.get(EventDetailsKeys.ENTITY_TYPE))),
        createFact("Publisher:", String.valueOf(detailsMap.get(EventDetailsKeys.PUBLISHER))),
        createFact("Time:", String.valueOf(detailsMap.get(EventDetailsKeys.TIME))),
        createFact("FQN:", String.valueOf(detailsMap.get(EventDetailsKeys.ENTITY_FQN))));
  }

  private List<TeamsMessage.Fact> createTestCaseDetailsFacts(
      Map<DQ_Template_Section, Map<Enum<?>, Object>> templateData) {

    List<TeamsMessage.Fact> list = List.of();

    if (templateData.containsKey(DQ_Template_Section.TEST_CASE_DETAILS)) {
      Map<Enum<?>, Object> testCaseDetails =
          templateData.get(DQ_Template_Section.TEST_CASE_DETAILS);

      list =
          List.of(
              createFact(
                  "ID:",
                  String.valueOf(testCaseDetails.getOrDefault(DQ_TestCaseDetailsKeys.ID, "-"))),
              createFact(
                  "Name:",
                  String.valueOf(testCaseDetails.getOrDefault(DQ_TestCaseDetailsKeys.NAME, "-"))),
              createFact(
                  "Owners:",
                  String.valueOf(testCaseDetails.getOrDefault(DQ_TestCaseDetailsKeys.OWNERS, "-"))),
              createFact(
                  "Tags:",
                  String.valueOf(testCaseDetails.getOrDefault(DQ_TestCaseDetailsKeys.TAGS, "-"))));
    }
    return list;
  }

  private List<TeamsMessage.Fact> createTestCaseResultFacts(
      Map<DQ_Template_Section, Map<Enum<?>, Object>> templateData) {
    List<TeamsMessage.Fact> list = List.of();

    if (templateData.containsKey(DQ_Template_Section.TEST_CASE_RESULT)) {
      Map<Enum<?>, Object> testCaseDetails = templateData.get(DQ_Template_Section.TEST_CASE_RESULT);

      if (!nullOrEmpty(testCaseDetails)) {
        list =
            List.of(
                createFact(
                    "Status:",
                    String.valueOf(
                        testCaseDetails.getOrDefault(DQ_TestCaseResultKeys.STATUS, "-"))),
                createFact(
                    "Parameter Value:",
                    String.valueOf(
                        testCaseDetails.getOrDefault(DQ_TestCaseResultKeys.PARAMETER_VALUE, "-"))),
                createFact(
                    "Result Message:",
                    String.valueOf(
                        testCaseDetails.getOrDefault(DQ_TestCaseResultKeys.RESULT_MESSAGE, "-"))));
      }
    }

    return list;
  }

  private List<TeamsMessage.Fact> createInspectionQueryFacts(
      Map<DQ_Template_Section, Map<Enum<?>, Object>> templateData) {
    List<TeamsMessage.Fact> list = List.of();

    if (templateData.containsKey(DQ_Template_Section.TEST_CASE_DETAILS)) {
      Map<Enum<?>, Object> testCaseDetails =
          templateData.get(DQ_Template_Section.TEST_CASE_DETAILS);

      if (!nullOrEmpty(testCaseDetails)
          && !nullOrEmpty(testCaseDetails.get(DQ_TestCaseDetailsKeys.INSPECTION_QUERY))) {
        list =
            List.of(
                createFact(
                    "Inspection Query:",
                    String.valueOf(
                        testCaseDetails.getOrDefault(
                            DQ_TestCaseDetailsKeys.INSPECTION_QUERY, "-"))));
      }
    }

    return list;
  }

  private List<TeamsMessage.Fact> createTestDefinitionFacts(
      Map<DQ_Template_Section, Map<Enum<?>, Object>> templateData) {
    List<TeamsMessage.Fact> list = List.of();

    if (templateData.containsKey(DQ_Template_Section.TEST_DEFINITION)) {
      Map<Enum<?>, Object> testCaseDetails = templateData.get(DQ_Template_Section.TEST_DEFINITION);

      if (!nullOrEmpty(testCaseDetails)) {
        list =
            List.of(
                createFact(
                    "Name:",
                    String.valueOf(
                        testCaseDetails.getOrDefault(
                            DQ_TestDefinitionKeys.TEST_DEFINITION_NAME, "-"))),
                createFact(
                    "Description:",
                    String.valueOf(
                        testCaseDetails.getOrDefault(
                            DQ_TestDefinitionKeys.TEST_DEFINITION_DESCRIPTION, "-"))));
      }
    }

    return list;
  }

  private List<TeamsMessage.Fact> createSampleDataFacts(
      Map<DQ_Template_Section, Map<Enum<?>, Object>> templateData) {
    List<TeamsMessage.Fact> list = List.of();

    if (templateData.containsKey(DQ_Template_Section.TEST_CASE_DETAILS)) {
      Map<Enum<?>, Object> testCaseDetails =
          templateData.get(DQ_Template_Section.TEST_CASE_DETAILS);

      if (!nullOrEmpty(testCaseDetails)) {
        list =
            List.of(
                createFact(
                    "Sample Data:",
                    String.valueOf(
                        testCaseDetails.getOrDefault(DQ_TestCaseDetailsKeys.SAMPLE_DATA, "-"))));
      }
    }

    return list;
  }

  private TeamsMessage createConnectionTestMessage(String publisherName) {
    Image imageItem = createOMImageMessage();

    Column column1 =
        Column.builder().type("Column").width("auto").items(List.of(imageItem)).build();

    TextBlock textBlock1 =
        TextBlock.builder()
            .type("TextBlock")
            .text("Connection Successful \u2705")
            .weight("Bolder")
            .size("Large")
            .wrap(true)
            .build();

    TextBlock textBlock2 =
        TextBlock.builder()
            .type("TextBlock")
            .text(applyBoldFormat("Publisher:") + publisherName)
            .wrap(true)
            .build();

    TextBlock textBlock3 =
        TextBlock.builder()
            .type("TextBlock")
            .text(
                "This is a Test Message, receiving this message confirms that you have successfully configured OpenMetadata to receive alerts.")
            .wrap(true)
            .build();

    Column column2 =
        Column.builder()
            .type("Column")
            .width("stretch")
            .items(List.of(textBlock1, textBlock2, textBlock3))
            .build();

    ColumnSet columnSet =
        ColumnSet.builder().type("ColumnSet").columns(List.of(column1, column2)).build();

    // AdaptiveCardContent
    AdaptiveCardContent adaptiveCardContent =
        AdaptiveCardContent.builder()
            .type("AdaptiveCard")
            .version("1.0")
            .body(
                List.of(
                    columnSet,
                    TextBlock.builder()
                        .type("TextBlock")
                        .text("OpenMetadata")
                        .weight("Lighter")
                        .size("Small")
                        .horizontalAlignment("Center")
                        .spacing("Medium")
                        .separator(true)
                        .build()))
            .build();

    Attachment attachment =
        Attachment.builder()
            .contentType("application/vnd.microsoft.card.adaptive")
            .content(adaptiveCardContent)
            .build();

    return TeamsMessage.builder().type("message").attachments(List.of(attachment)).build();
  }

  private Map<General_Template_Section, Map<Enum<?>, Object>> buildGeneralTemplateData(
      String publisherName, ChangeEvent event, OutgoingMessage outgoingMessage) {

    TemplateDataBuilder<General_Template_Section> builder = new TemplateDataBuilder<>();

    // Use General_Template_Section directly
    builder
        .add(
            General_Template_Section.EVENT_DETAILS,
            EventDetailsKeys.EVENT_TYPE,
            event.getEventType().value())
        .add(
            General_Template_Section.EVENT_DETAILS,
            EventDetailsKeys.UPDATED_BY,
            event.getUserName())
        .add(
            General_Template_Section.EVENT_DETAILS,
            EventDetailsKeys.ENTITY_TYPE,
            event.getEntityType())
        .add(
            General_Template_Section.EVENT_DETAILS,
            EventDetailsKeys.ENTITY_FQN,
            getFQNForChangeEventEntity(event))
        .add(General_Template_Section.EVENT_DETAILS, EventDetailsKeys.PUBLISHER, publisherName)
        .add(
            General_Template_Section.EVENT_DETAILS,
            EventDetailsKeys.TIME,
            new Date(event.getTimestamp()).toString())
        .add(
            General_Template_Section.EVENT_DETAILS,
            EventDetailsKeys.OUTGOING_MESSAGE,
            outgoingMessage);

    return builder.build();
  }

  private Map<DQ_Template_Section, Map<Enum<?>, Object>> buildDQTemplateData(
      String publisherName, ChangeEvent event, OutgoingMessage outgoingMessage) {

    TemplateDataBuilder<DQ_Template_Section> builder = new TemplateDataBuilder<>();

    // Use DQ_Template_Section directly
    builder
        .add(
            DQ_Template_Section.EVENT_DETAILS,
            EventDetailsKeys.EVENT_TYPE,
            event.getEventType().value())
        .add(DQ_Template_Section.EVENT_DETAILS, EventDetailsKeys.UPDATED_BY, event.getUserName())
        .add(DQ_Template_Section.EVENT_DETAILS, EventDetailsKeys.ENTITY_TYPE, event.getEntityType())
        .add(
            DQ_Template_Section.EVENT_DETAILS,
            EventDetailsKeys.ENTITY_FQN,
            getFQNForChangeEventEntity(event))
        .add(DQ_Template_Section.EVENT_DETAILS, EventDetailsKeys.PUBLISHER, publisherName)
        .add(
            DQ_Template_Section.EVENT_DETAILS,
            EventDetailsKeys.TIME,
            new Date(event.getTimestamp()).toString())
        .add(DQ_Template_Section.EVENT_DETAILS, EventDetailsKeys.OUTGOING_MESSAGE, outgoingMessage);

    return builder.build();
  }

  private TextBlock createHeader() {
    return TextBlock.builder()
        .type("TextBlock")
        .text(applyBoldFormat("Change Event Details"))
        .size("Large")
        .weight("Bolder")
        .wrap(true)
        .build();
  }

  private TextBlock createFooterMessage() {
    return TextBlock.builder()
        .type("TextBlock")
        .text("Change Event By OpenMetadata.")
        .size("Small")
        .weight("Lighter")
        .horizontalAlignment("Center")
        .spacing("Medium")
        .separator(true)
        .build();
  }

  private TextBlock createBoldTextBlock(String text) {
    return TextBlock.builder()
        .type("TextBlock")
        .text(applyBoldFormat(text))
        .weight("Bolder")
        .wrap(true)
        .build();
  }

  private TextBlock createDivider() {
    return TextBlock.builder()
        .type("TextBlock")
        .text(" ")
        .separator(true)
        .spacing("Medium")
        .build();
  }

  private TeamsMessage.Fact createFact(String title, String value) {
    return TeamsMessage.Fact.builder().title(applyBoldFormat(title)).value(value).build();
  }

  private String applyBoldFormat(String title) {
    return String.format(getBoldWithSpace(), title);
  }

  private Image createOMImageMessage() {
    return Image.builder().type("Image").url("https://imgur.com/kOOPEG4.png").size("Small").build();
  }
}
