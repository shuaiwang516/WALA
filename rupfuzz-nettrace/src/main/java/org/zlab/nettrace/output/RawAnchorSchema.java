package org.zlab.nettrace.output;

public final class RawAnchorSchema {
  private RawAnchorSchema() {}

  public static final String JSON_SCHEMA =
      "{\n"
          + "  \"$schema\": \"https://json-schema.org/draft/2020-12/schema\",\n"
          + "  \"title\": \"Raw Net Anchors\",\n"
          + "  \"type\": \"array\",\n"
          + "  \"items\": {\n"
          + "    \"type\": \"object\",\n"
          + "    \"required\": [\n"
          + "      \"anchorId\",\n"
          + "      \"role\",\n"
          + "      \"source\",\n"
          + "      \"callerClass\",\n"
          + "      \"callerMethod\",\n"
          + "      \"lineNumber\",\n"
          + "      \"instructionIndex\",\n"
          + "      \"invokedClass\",\n"
          + "      \"invokedMethod\",\n"
          + "      \"invokedDescriptor\",\n"
          + "      \"confidence\"\n"
          + "    ],\n"
          + "    \"properties\": {\n"
          + "      \"anchorId\": {\"type\": \"string\"},\n"
          + "      \"role\": {\"type\": \"string\", \"enum\": [\"SEND\", \"RECV\"]},\n"
          + "      \"source\": {\"type\": \"string\", \"enum\": [\"GENERIC\", \"PROFILE\"]},\n"
          + "      \"profile\": {\"type\": \"string\"},\n"
          + "      \"reason\": {\"type\": \"string\"},\n"
          + "      \"callerClass\": {\"type\": \"string\"},\n"
          + "      \"callerMethod\": {\"type\": \"string\"},\n"
          + "      \"callerDescriptor\": {\"type\": \"string\"},\n"
          + "      \"lineNumber\": {\"type\": \"integer\"},\n"
          + "      \"instructionIndex\": {\"type\": \"integer\"},\n"
          + "      \"invokedClass\": {\"type\": \"string\"},\n"
          + "      \"invokedMethod\": {\"type\": \"string\"},\n"
          + "      \"invokedDescriptor\": {\"type\": \"string\"},\n"
          + "      \"confidence\": {\"type\": \"number\"}\n"
          + "    }\n"
          + "  }\n"
          + "}\n";
}
