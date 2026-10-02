package edu.njit.cs643;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.CreateQueueRequest;
import software.amazon.awssdk.services.sqs.model.DeleteMessageRequest;
import software.amazon.awssdk.services.sqs.model.Message;
import software.amazon.awssdk.services.sqs.model.QueueAttributeName;
import software.amazon.awssdk.services.sqs.model.ReceiveMessageRequest;
import software.amazon.awssdk.services.textract.TextractClient;
import software.amazon.awssdk.services.textract.model.Block;
import software.amazon.awssdk.services.textract.model.BlockType;
import software.amazon.awssdk.services.textract.model.DetectDocumentTextRequest;
import software.amazon.awssdk.services.textract.model.DetectDocumentTextResponse;
import software.amazon.awssdk.services.textract.model.Document;
import software.amazon.awssdk.services.textract.model.S3Object;
import software.amazon.awssdk.services.textract.model.TextractException;

/**
 * CS 643 Project 1, EC2 Instance B.
 *
 * Long-polls the SQS FIFO queue for image indexes sent by Instance A, runs
 * AWS Textract on each corresponding S3 image, and stops when it receives
 * "-1". Every image that reaches this instance already contains a face, so
 * any image where Textract finds text contains both a face and text. Those
 * images and their text are written to output.txt.
 */
public class TextRecognition {

    private static final String BUCKET = "cs643-f26-project1";
    private static final String QUEUE_NAME = "cs643-project1-queue.fifo";
    private static final String END_MARKER = "-1";
    private static final String OUTPUT_FILE = "output.txt";
    private static final Region REGION = Region.US_EAST_1;

    public static void main(String[] args) throws IOException {
        try (SqsClient sqs = SqsClient.builder().region(REGION).build();
             TextractClient textract = TextractClient.builder().region(REGION).build()) {

            // Same idempotent call as Instance A, so either instance can start first.
            String queueUrl = getOrCreateQueue(sqs);
            System.out.println("Using queue: " + queueUrl);
            System.out.println("Waiting for image indexes from Instance A...");
            System.out.println();

            Map<String, String> results = new LinkedHashMap<>();
            boolean done = false;

            while (!done) {
                List<Message> messages = sqs.receiveMessage(ReceiveMessageRequest.builder()
                        .queueUrl(queueUrl)
                        .maxNumberOfMessages(10)
                        .waitTimeSeconds(20)      // long polling
                        .visibilityTimeout(60)
                        .build()).messages();

                for (Message message : messages) {
                    String key = message.body().trim();

                    if (END_MARKER.equals(key)) {
                        delete(sqs, queueUrl, message);
                        System.out.println("Received " + END_MARKER + ". No more images to process.");
                        done = true;
                        break;
                    }

                    try {
                        String text = extractText(textract, key);
                        if (text.isEmpty()) {
                            System.out.printf("%-10s face only, no text found%n", key);
                        } else {
                            results.put(key, text);
                            System.out.printf("%-10s face + text found: %s%n", key, text.replace("\n", " | "));
                        }
                    } catch (TextractException e) {
                        System.err.println(key + ": Textract error: " + e.awsErrorDetails().errorMessage());
                    }

                    // Delete only after processing, so a crash leaves the message in the queue.
                    delete(sqs, queueUrl, message);
                }
            }

            writeOutput(results);
        }
    }

    /** Creates the FIFO queue if it does not exist and returns its URL. */
    static String getOrCreateQueue(SqsClient sqs) {
        CreateQueueRequest request = CreateQueueRequest.builder()
                .queueName(QUEUE_NAME)
                .attributes(Map.of(QueueAttributeName.FIFO_QUEUE, "true"))
                .build();
        return sqs.createQueue(request).queueUrl();
    }

    /** Runs Textract DetectDocumentText on the S3 image and returns its lines joined by newlines. */
    static String extractText(TextractClient textract, String key) {
        DetectDocumentTextRequest request = DetectDocumentTextRequest.builder()
                .document(Document.builder()
                        .s3Object(S3Object.builder().bucket(BUCKET).name(key).build())
                        .build())
                .build();

        DetectDocumentTextResponse response = textract.detectDocumentText(request);
        List<String> lines = new ArrayList<>();
        for (Block block : response.blocks()) {
            if (block.blockType() == BlockType.LINE && block.text() != null && !block.text().isBlank()) {
                lines.add(block.text().trim());
            }
        }
        return String.join("\n", lines);
    }

    static void delete(SqsClient sqs, String queueUrl, Message message) {
        sqs.deleteMessage(DeleteMessageRequest.builder()
                .queueUrl(queueUrl)
                .receiptHandle(message.receiptHandle())
                .build());
    }

    /** Writes the images containing both a face and text, with their text, to output.txt. */
    static void writeOutput(Map<String, String> results) throws IOException {
        StringBuilder out = new StringBuilder();
        out.append("Images containing both a face and text\n");
        out.append("======================================\n\n");

        if (results.isEmpty()) {
            out.append("No images contained both a face and text.\n");
        }
        for (Map.Entry<String, String> entry : results.entrySet()) {
            out.append("Image index: ").append(entry.getKey()).append('\n');
            out.append("Extracted text:\n");
            for (String line : entry.getValue().split("\n")) {
                out.append("  ").append(line).append('\n');
            }
            out.append('\n');
        }

        Path path = Path.of(OUTPUT_FILE);
        Files.writeString(path, out.toString());
        System.out.println();
        System.out.println("Wrote " + results.size() + " result(s) to " + path.toAbsolutePath());
    }
}
