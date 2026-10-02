package edu.njit.cs643;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import software.amazon.awssdk.regions.Region;
import software.amazon.awssdk.services.rekognition.RekognitionClient;
import software.amazon.awssdk.services.rekognition.model.DetectFacesRequest;
import software.amazon.awssdk.services.rekognition.model.DetectFacesResponse;
import software.amazon.awssdk.services.rekognition.model.FaceDetail;
import software.amazon.awssdk.services.rekognition.model.Image;
import software.amazon.awssdk.services.rekognition.model.RekognitionException;
import software.amazon.awssdk.services.s3.S3Client;
import software.amazon.awssdk.services.s3.model.ListObjectsV2Request;
import software.amazon.awssdk.services.s3.model.S3Object;
import software.amazon.awssdk.services.sqs.SqsClient;
import software.amazon.awssdk.services.sqs.model.CreateQueueRequest;
import software.amazon.awssdk.services.sqs.model.QueueAttributeName;
import software.amazon.awssdk.services.sqs.model.SendMessageRequest;

/**
 * CS 643 Project 1, EC2 Instance A.
 *
 * Reads the images from the public S3 bucket, runs AWS Rekognition face
 * detection on each one, and sends the index (S3 key) of every image with a
 * face above 75% confidence to an SQS FIFO queue. When finished, it sends
 * "-1" so Instance B knows no more images are coming.
 */
public class FaceDetection {

    private static final String BUCKET = "cs643-f26-project1";
    private static final String QUEUE_NAME = "cs643-project1-queue.fifo";
    private static final String GROUP_ID = "image-indexes";
    private static final String END_MARKER = "-1";
    private static final float MIN_CONFIDENCE = 75.0f;
    private static final Region REGION = Region.US_EAST_1;

    public static void main(String[] args) {
        try (S3Client s3 = S3Client.builder().region(REGION).build();
             RekognitionClient rekognition = RekognitionClient.builder().region(REGION).build();
             SqsClient sqs = SqsClient.builder().region(REGION).build()) {

            // Creating the queue is idempotent, so it does not matter
            // whether Instance A or Instance B starts first.
            String queueUrl = getOrCreateQueue(sqs);
            System.out.println("Using queue: " + queueUrl);

            List<String> imageKeys = listImages(s3);
            System.out.println("Found " + imageKeys.size() + " images in s3://" + BUCKET);
            System.out.println();

            int sent = 0;
            for (String key : imageKeys) {
                try {
                    float best = detectBestFaceConfidence(rekognition, key);
                    if (best > MIN_CONFIDENCE) {
                        send(sqs, queueUrl, key);
                        sent++;
                        System.out.printf("%-10s face detected (%.2f%%) -> sent to SQS%n", key, best);
                    } else {
                        System.out.printf("%-10s no face above %.0f%% (best %.2f%%)%n",
                                key, MIN_CONFIDENCE, best);
                    }
                } catch (RekognitionException e) {
                    System.err.println(key + ": Rekognition error: " + e.awsErrorDetails().errorMessage());
                }
            }

            send(sqs, queueUrl, END_MARKER);
            System.out.println();
            System.out.println("Done. " + sent + " image index(es) sent. Sent " + END_MARKER
                    + " to signal Instance B.");
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

    /** Lists the image keys in the bucket, sorted numerically (1.jpg, 2.jpg, ... 10.jpg). */
    static List<String> listImages(S3Client s3) {
        List<String> keys = new ArrayList<>();
        ListObjectsV2Request request = ListObjectsV2Request.builder().bucket(BUCKET).build();
        for (S3Object obj : s3.listObjectsV2Paginator(request).contents()) {
            String lower = obj.key().toLowerCase();
            if (lower.endsWith(".jpg") || lower.endsWith(".jpeg") || lower.endsWith(".png")) {
                keys.add(obj.key());
            }
        }
        Comparator<String> byNumber = Comparator.comparingInt(FaceDetection::numericPart);
        keys.sort(byNumber.thenComparing(Comparator.naturalOrder()));
        return keys;
    }

    private static int numericPart(String key) {
        String digits = key.replaceAll("\\D", "");
        if (digits.isEmpty()) {
            return Integer.MAX_VALUE;
        }
        try {
            return Integer.parseInt(digits);
        } catch (NumberFormatException e) {
            return Integer.MAX_VALUE;
        }
    }

    /** Runs Rekognition DetectFaces on the S3 image and returns the highest face confidence (0 if none). */
    static float detectBestFaceConfidence(RekognitionClient rekognition, String key) {
        DetectFacesRequest request = DetectFacesRequest.builder()
                .image(Image.builder()
                        .s3Object(software.amazon.awssdk.services.rekognition.model.S3Object.builder()
                                .bucket(BUCKET)
                                .name(key)
                                .build())
                        .build())
                .build();

        DetectFacesResponse response = rekognition.detectFaces(request);
        float best = 0f;
        for (FaceDetail face : response.faceDetails()) {
            if (face.confidence() != null && face.confidence() > best) {
                best = face.confidence();
            }
        }
        return best;
    }

    /** Sends one message to the FIFO queue. A random dedup ID lets repeated runs resend the same index. */
    static void send(SqsClient sqs, String queueUrl, String body) {
        sqs.sendMessage(SendMessageRequest.builder()
                .queueUrl(queueUrl)
                .messageBody(body)
                .messageGroupId(GROUP_ID)
                .messageDeduplicationId(UUID.randomUUID().toString())
                .build());
    }
}
