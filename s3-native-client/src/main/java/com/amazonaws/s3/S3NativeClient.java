package com.amazonaws.s3;

import com.amazonaws.s3.model.*;
import software.amazon.awssdk.crt.CRT;
import software.amazon.awssdk.crt.CrtRuntimeException;
import software.amazon.awssdk.crt.auth.credentials.CredentialsProvider;
import software.amazon.awssdk.crt.http.HttpHeader;
import software.amazon.awssdk.crt.http.HttpRequest;
import software.amazon.awssdk.crt.http.HttpRequestBodyStream;
import software.amazon.awssdk.crt.io.ClientBootstrap;
import software.amazon.awssdk.crt.s3.*;

import java.nio.ByteBuffer;
import java.time.Instant;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;

public class S3NativeClient implements  AutoCloseable {
    private final S3Client s3Client;
    private final String signingRegion;

    public S3NativeClient(final String signingRegion,
                          final ClientBootstrap clientBootstrap,
                          final CredentialsProvider credentialsProvider,
                          final long partSizeBytes,
                          final double targetThroughputGbps) {
        //keep signing region to construct Host header per-request
        this.signingRegion = signingRegion;
        final S3ClientOptions clientOptions = new S3ClientOptions()
                .withClientBootstrap(clientBootstrap)
                .withCredentialsProvider(credentialsProvider)
                .withRegion(signingRegion)
                .withPartSize(partSizeBytes)
                .withThroughputTargetGbps(targetThroughputGbps);
        s3Client = new S3Client(clientOptions);
    }

    public CompletableFuture<GetObjectOutput> getObject(GetObjectRequest request,
                                                        final ResponseDataConsumer dataHandler) {
        final CompletableFuture<GetObjectOutput> resultFuture = new CompletableFuture<>();
        final GetObjectOutput.Builder resultBuilder = GetObjectOutput.builder();
        final S3MetaRequestResponseHandler responseHandler = new S3MetaRequestResponseHandler() {
            @Override
            public void onResponseHeaders(final int statusCode, final HttpHeader[] headers) {
                for (int headerIndex = 0; headerIndex < headers.length; ++headerIndex) {
                    try {
                        populateGetObjectOutputHeader(resultBuilder, headers[headerIndex]);
                    } catch (Exception e) {
                        resultFuture.completeExceptionally(
                                new RuntimeException( String.format("Could not process response header {%s}: "
                                        + headers[headerIndex].getName()), e));
                    }
                }
                dataHandler.onResponseHeaders(statusCode, headers);
            }

            @Override
            public int onResponseBody(byte[] bodyBytesIn, long objectRangeStart, long objectRangeEnd) {
                dataHandler.onResponseData(bodyBytesIn);
                return 0;
            }

            @Override
            public void onFinished(int errorCode) {
                CrtRuntimeException ex = null;
                try {
                    if (errorCode != CRT.AWS_CRT_SUCCESS) {
                         ex = new CrtRuntimeException(errorCode);
                        dataHandler.onException(ex);
                    } else {
                        dataHandler.onFinished();
                    }
                } catch (Exception e) { /* ignore user callback exception */
                } finally {
                    if (ex != null) {
                        resultFuture.completeExceptionally(ex);
                    }
                    else {
                        resultFuture.complete(resultBuilder.build());
                    }
                }
            }
        };

        List<HttpHeader> headers = new LinkedList<>();
        // TODO: additional logic needed for *special* partitions
        headers.add(new HttpHeader("Host", request.bucket() + ".s3."+ signingRegion + ".amazonaws.com"));
        populateGetObjectRequestHeaders(header -> headers.add(header), request);
        final StringBuilder keyString = new StringBuilder("/" + request.key());
        final Map<String, String> requestParams = new HashMap<>();
        if (request.partNumber() != null) {
            requestParams.put("PartNumber", Integer.toString(request.partNumber()));
        }

        HttpRequest httpRequest = new HttpRequest("GET", keyString.toString(),
                headers.toArray(new HttpHeader[0]), null);

        S3MetaRequestOptions metaRequestOptions = new S3MetaRequestOptions()
                .withMetaRequestType(S3MetaRequestOptions.MetaRequestType.GET_OBJECT)
                .withHttpRequest(httpRequest)
                .withResponseHandler(responseHandler);

        try (final S3MetaRequest metaRequest = s3Client.makeMetaRequest(metaRequestOptions)) {
            return resultFuture;
        }
    }

    public CompletableFuture<PutObjectOutput> putObject(PutObjectRequest request,
                                                        final RequestDataSupplier requestDataSupplier) {
        final CompletableFuture<PutObjectOutput> resultFuture = new CompletableFuture<>();
        final PutObjectOutput.Builder resultBuilder = PutObjectOutput.builder();
        HttpRequestBodyStream payloadStream = new HttpRequestBodyStream() {
            @Override
            public boolean sendRequestBody(final ByteBuffer outBuffer) {
                try {
                    if (outBuffer.hasArray()) {
                        return requestDataSupplier.getRequestBytes(outBuffer.array());
                    } else {
                        byte[] buffer = new byte[outBuffer.capacity()];
                        boolean returnFlag = requestDataSupplier.getRequestBytes(buffer);
                        outBuffer.put(buffer);  //assert outBuffer.remaining() == 0
                        return returnFlag;
                    }
                } catch (Exception e) {
                    resultFuture.completeExceptionally(e);
                    return true;
                }
            }

            @Override
            public boolean resetPosition() {
                try {
                    return requestDataSupplier.resetPosition();
                } catch (Exception e) {
                    return false;
                }
            }

            @Override
            public long getLength() {
                return request.contentLength();
            }
        };

        final List<HttpHeader> headers = new LinkedList<>();
        //TODO: additional logic needed for *special* partitions
        headers.add(new HttpHeader("Host", request.bucket() + ".s3." + signingRegion + ".amazonaws.com"));
        populatePutObjectRequestHeaders(header -> headers.add(header), request);
        final StringBuilder keyString = new StringBuilder("/" + request.key());
        HttpRequest httpRequest = new HttpRequest("PUT", keyString.toString(),
                headers.toArray(new HttpHeader[0]), payloadStream);

        final S3MetaRequestResponseHandler responseHandler = new S3MetaRequestResponseHandler() {
            @Override
            public void onResponseHeaders(final int statusCode, final HttpHeader[] headers) {
                for (int headerIndex = 0; headerIndex < headers.length; ++headerIndex) {
                    try {
                        populatePutObjectOutputHeader(resultBuilder, headers[headerIndex]);
                    } catch (Exception e) {
                        resultFuture.completeExceptionally(
                                new RuntimeException( String.format("Could not process response header {%s}: "
                                        + headers[headerIndex].getName()), e));
                    }
                }
                requestDataSupplier.onResponseHeaders(statusCode, headers);
            }

            @Override
            public int onResponseBody(byte[] bodyBytesIn, long objectRangeStart, long objectRangeEnd) {
                return 0;
            }

            @Override
            public void onFinished(int errorCode) {
                if (errorCode == CRT.AWS_CRT_SUCCESS) {
                    resultFuture.complete(resultBuilder.build());
                } else {
                    resultFuture.completeExceptionally(new CrtRuntimeException(errorCode, CRT.awsErrorString(errorCode)));
                }
            }
        };
        S3MetaRequestOptions metaRequestOptions = new S3MetaRequestOptions()
                .withMetaRequestType(S3MetaRequestOptions.MetaRequestType.PUT_OBJECT)
                .withHttpRequest(httpRequest)
                .withResponseHandler(responseHandler);

        try (final S3MetaRequest metaRequest = s3Client.makeMetaRequest(metaRequestOptions)) {
            return resultFuture;
        }
    }

    @Override
    public void close() {
        if (s3Client != null) {
            s3Client.close();
        }
    }

    protected void populateGetObjectRequestHeaders(final Consumer<HttpHeader> headerConsumer,
                                                   final GetObjectRequest request) {
        //https://docs.aws.amazon.com/AmazonS3/latest/API/API_GetObject.html
        if (request.ifMatch() != null) {
            headerConsumer.accept(new HttpHeader("If-Match", request.ifMatch()));
        }
        if (request.ifMatch() != null) {
            headerConsumer.accept(new HttpHeader("If-Modified-Since",
                    DateTimeFormatter.RFC_1123_DATE_TIME.format(request.ifModifiedSince())));
        }
        if (request.ifNoneMatch() != null) {
            headerConsumer.accept(new HttpHeader("If-None-Match", request.ifNoneMatch()));
        }
        if (request.ifUnmodifiedSince() != null) {
            headerConsumer.accept(new HttpHeader("If-Unmodified-Since",
                    DateTimeFormatter.RFC_1123_DATE_TIME.format(request.ifUnmodifiedSince())));
        }
        if (request.range() != null) {
            headerConsumer.accept(new HttpHeader("Range", request.range()));
        }
        if (request.sSECustomerAlgorithm() != null) {
            headerConsumer.accept(new HttpHeader("x-amz-server-side-encryption-customer-algorithm",
                    request.sSECustomerAlgorithm()));
        }
        if (request.sSECustomerKey() != null) {
            headerConsumer.accept(new HttpHeader("x-amz-server-side-encryption-customer-key",
                    request.sSECustomerKey()));
        }
        if (request.sSECustomerKeyMD5() != null) {
            headerConsumer.accept(new HttpHeader("x-amz-server-side-encryption-customer-key-MD5",
                    request.sSECustomerKeyMD5()));
        }
        if (request.requestPayer() != null) {
            headerConsumer.accept(new HttpHeader("x-amz-request-payer", request.requestPayer().name()));
        }
        if (request.expectedBucketOwner() != null) {
            headerConsumer.accept(new HttpHeader("x-amz-expected-bucket-owner", request.expectedBucketOwner()));
        }
        //in progress, but shape of method left here
    }

    protected void populateGetObjectOutputHeader(final GetObjectOutput.Builder builder, final HttpHeader header) {
        //https://docs.aws.amazon.com/AmazonS3/latest/API/API_GetObject.html
        if ("x-amz-id-2".equalsIgnoreCase(header.getName())) {
            //TODO: customers want this for tracing availability issues
        } else if ("x-amz-request-id".equalsIgnoreCase(header.getName())) {
            //TODO: customers want this for tracing availability issues
        } else if ("Last-Modified".equalsIgnoreCase(header.getName())) {
            builder.lastModified(DateTimeFormatter.RFC_1123_DATE_TIME.parse(header.getValue(), Instant::from));
        } else if ("ETag".equalsIgnoreCase(header.getName())) {
            builder.eTag(header.getValue());
        } else if ("x-amz-version-id".equalsIgnoreCase(header.getName())) {
            builder.versionId(header.getValue());
        } else if ("Accept-Ranges".equalsIgnoreCase(header.getName())) {
            builder.acceptRanges(header.getValue());
        } else if ("Content-Type".equalsIgnoreCase(header.getName())) {
            builder.contentType(header.getValue());
        } else if ("Content-Length".equalsIgnoreCase(header.getName())) {
            builder.contentLength(Long.parseLong(header.getValue()));
        } else {
            //unhandled header is not necessarily bad, but potentially logged
        }
    }

    protected void populatePutObjectRequestHeaders(final Consumer<HttpHeader> headerConsumer,
                                                   final PutObjectRequest request) {
        //https://docs.aws.amazon.com/AmazonS3/latest/API/API_PutObject.html
        if (request.aCL() != null) {
            headerConsumer.accept(new HttpHeader("x-amz-acl", request.aCL().name()));
        }
        if (request.cacheControl() != null) {
            headerConsumer.accept(new HttpHeader("Cache-Control", request.cacheControl()));
        }
        if (request.contentDisposition() != null) {
            headerConsumer.accept(new HttpHeader("Content-Disposition", request.contentDisposition()));
        }
        if (request.contentEncoding() != null) {
            headerConsumer.accept(new HttpHeader("Content-Encoding", request.contentEncoding()));
        }
        if (request.contentLanguage() != null) {
            headerConsumer.accept(new HttpHeader("Content-Language", request.contentLanguage()));
        }
        if (request.contentLength() != null) {
            headerConsumer.accept(new HttpHeader("Content-Length", Long.toString(request.contentLength())));
        }
        if (request.contentMD5() != null) {
            headerConsumer.accept(new HttpHeader("Content-MD5", request.contentMD5()));
        }
        if (request.contentType() != null) {
            headerConsumer.accept(new HttpHeader("Content-Type", request.contentType()));
        }
        if (request.expires() != null) {
            headerConsumer.accept(new HttpHeader("Expires",
                    DateTimeFormatter.RFC_1123_DATE_TIME.format(request.expires())));
        }
        if (request.grantFullControl() != null) {
            headerConsumer.accept(new HttpHeader("x-amz-grant-full-control", request.grantFullControl()));
        }
        if (request.grantRead() != null) {
            headerConsumer.accept(new HttpHeader("x-amz-grant-read", request.grantRead()));
        }
        if (request.grantReadACP() != null) {
            headerConsumer.accept(new HttpHeader("x-amz-grant-read-acp", request.grantReadACP()));
        }
        if (request.grantWriteACP() != null) {
            headerConsumer.accept(new HttpHeader("x-amz-grant-write-acp", request.grantWriteACP()));
        }
        if (request.serverSideEncryption() != null) {
            headerConsumer.accept(new HttpHeader("x-amz-server-side-encryption",
                    request.serverSideEncryption().name()));
        }
        if (request.storageClass() != null) {
            headerConsumer.accept(new HttpHeader("x-amz-storage-class", request.storageClass().name()));
        }
        if (request.websiteRedirectLocation() != null) {
            headerConsumer.accept(new HttpHeader("x-amz-website-redirect-location",
                    request.websiteRedirectLocation()));
        }
        if (request.sSECustomerAlgorithm() != null) {
            headerConsumer.accept(new HttpHeader("x-amz-server-side-encryption-customer-algorithm",
                    request.sSECustomerAlgorithm()));
        }
        if (request.sSECustomerKey() != null) {
            headerConsumer.accept(new HttpHeader("x-amz-server-side-encryption-customer-key",
                    request.sSECustomerKey()));
        }
        if (request.sSECustomerKeyMD5() != null) {
            headerConsumer.accept(new HttpHeader("x-amz-server-side-encryption-customer-key-MD5",
                    request.sSECustomerKeyMD5()));
        }
        if (request.sSEKMSKeyId() != null) {
            headerConsumer.accept(new HttpHeader("x-amz-server-side-encryption-aws-kms-key-id",
                    request.sSEKMSKeyId()));
        }
        if (request.sSEKMSEncryptionContext() != null) {
            headerConsumer.accept(new HttpHeader("x-amz-server-side-encryption-context",
                    request.sSEKMSEncryptionContext()));
        }
        if (request.bucketKeyEnabled() != null) {
            headerConsumer.accept(new HttpHeader("x-amz-server-side-encryption-bucket-key-enabled",
                    Boolean.toString(request.bucketKeyEnabled())));
        }
        if (request.requestPayer() != null) {
            headerConsumer.accept(new HttpHeader("x-amz-request-payer", request.requestPayer().name()));
        }
        if (request.tagging() != null) {
            headerConsumer.accept(new HttpHeader("x-amz-tagging", request.tagging()));
        }
        if (request.objectLockMode() != null) {
            headerConsumer.accept(new HttpHeader("x-amz-object-lock-mode", request.objectLockMode().name()));
        }
        if (request.objectLockRetainUntilDate() != null) {
            headerConsumer.accept(new HttpHeader("x-amz-object-lock-retain-until-date",
                    DateTimeFormatter.RFC_1123_DATE_TIME.format(request.objectLockRetainUntilDate())));
        }
        if (request.objectLockLegalHoldStatus() != null) {
            headerConsumer.accept(new HttpHeader("x-amz-object-lock-legal-hold",
                    request.objectLockLegalHoldStatus().name()));
        }
        if (request.expectedBucketOwner() != null) {
            headerConsumer.accept(new HttpHeader("x-amz-expected-bucket-owner", request.expectedBucketOwner()));
        }
    }

    protected void populatePutObjectOutputHeader(final PutObjectOutput.Builder builder, final HttpHeader header) {
        //https://docs.aws.amazon.com/AmazonS3/latest/API/API_PutObject.html
        if ("xamz-id-2".equalsIgnoreCase(header.getName())) {
            //TODO: customers want this for tracing availability issues
        } else if ("x-amz-request-id".equalsIgnoreCase(header.getName())) {
            //TODO: customers want this for tracing availability issues
        } else if ("x-amz-version-id".equalsIgnoreCase(header.getName())) {
            builder.versionId(header.getValue());
        } else if ("ETag".equalsIgnoreCase(header.getName())) {
            builder.eTag(header.getValue());
        } else if ("x-amz-expiration".equalsIgnoreCase(header.getName())) {
            builder.expiration(header.getValue());
        } else if ("x-amz-server-side-encryption".equalsIgnoreCase(header.getName())) {
            builder.serverSideEncryption(ServerSideEncryption.fromValue(header.getValue()));
        } else if ("x-amz-server-side-encryption-aws-kms-key-id".equalsIgnoreCase(header.getName())) {
            builder.sSEKMSKeyId(header.getValue());
        } else if ("x-amz-server-side-encryption-bucket-key-enabled".equalsIgnoreCase(header.getName())) {
            builder.bucketKeyEnabled(Boolean.parseBoolean(header.getValue()));  //need verification
        } else if ("x-amz-request-charged".equalsIgnoreCase(header.getName())) {
            builder.requestCharged(RequestCharged.fromValue(header.getValue()));
        }
    }
}
