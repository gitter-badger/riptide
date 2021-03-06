package org.zalando.riptide;

import com.google.common.collect.ImmutableMultimap;
import com.google.common.collect.Lists;
import com.google.common.collect.Multimap;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.client.ClientHttpRequest;
import org.springframework.http.client.ClientHttpRequestFactory;
import org.springframework.http.client.ClientHttpResponse;
import org.zalando.fauxpas.ThrowingUnaryOperator;

import javax.annotation.Nullable;
import java.io.IOException;
import java.net.URI;
import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

import static com.google.common.net.HttpHeaders.ACCEPT;
import static com.google.common.net.HttpHeaders.CONTENT_TYPE;
import static com.google.common.net.HttpHeaders.IF_MATCH;
import static com.google.common.net.HttpHeaders.IF_MODIFIED_SINCE;
import static com.google.common.net.HttpHeaders.IF_NONE_MATCH;
import static com.google.common.net.HttpHeaders.IF_UNMODIFIED_SINCE;
import static java.lang.String.join;
import static java.time.ZoneOffset.UTC;
import static java.time.format.DateTimeFormatter.RFC_1123_DATE_TIME;
import static java.util.stream.Collectors.joining;
import static org.zalando.fauxpas.FauxPas.throwingFunction;
import static org.zalando.fauxpas.FauxPas.throwingRunnable;

final class Requester extends QueryStage {

    private final Executor executor;
    private final ClientHttpRequestFactory requestFactory;
    private final MessageWorker worker;
    private final RequestArguments arguments;
    private final Plugin plugin;

    private final ImmutableMultimap<String, String> query;
    private final ImmutableMultimap<String, String> headers;

    Requester(final Executor executor, final ClientHttpRequestFactory requestFactory,
            final MessageWorker worker, final RequestArguments arguments, final Plugin plugin,
            final ImmutableMultimap<String, String> query,
            final ImmutableMultimap<String, String> headers) {
        this.executor = executor;
        this.requestFactory = requestFactory;
        this.worker = worker;
        this.arguments = arguments;
        this.plugin = plugin;
        this.query = query;
        this.headers = headers;
    }

    private Requester(final Requester requester, final ImmutableMultimap<String, String> query,
            final ImmutableMultimap<String, String> headers) {
        this(requester.executor, requester.requestFactory, requester.worker, requester.arguments,
                requester.plugin, query, headers);
    }

    @Override
    public QueryStage queryParam(final String name, final String value) {
        return new Requester(this, ImmutableMultimap.<String, String>builder()
                .putAll(query)
                .put(name, value)
                .build(),
                headers);
    }

    @Override
    public QueryStage queryParams(final Multimap<String, String> params) {
        return new Requester(this, ImmutableMultimap.<String, String>builder()
                .putAll(query)
                .putAll(params)
                .build(),
                headers);
    }

    @Override
    public HeaderStage accept(final MediaType acceptableMediaType, final MediaType... acceptableMediaTypes) {
        return accept(Lists.asList(acceptableMediaType, acceptableMediaTypes));
    }

    @Override
    public HeaderStage accept(final Collection<MediaType> acceptableMediaTypes) {
        return header(ACCEPT, acceptableMediaTypes.stream()
                .map(Objects::toString)
                .collect(joining(", ")));
    }

    @Override
    public HeaderStage contentType(final MediaType contentType) {
        return header(CONTENT_TYPE, contentType.toString());
    }

    @Override
    public HeaderStage ifModifiedSince(final OffsetDateTime since) {
        return header(IF_MODIFIED_SINCE, toHttpdate(since));
    }

    @Override
    public HeaderStage ifUnmodifiedSince(final OffsetDateTime since) {
        return header(IF_UNMODIFIED_SINCE, toHttpdate(since));
    }

    private String toHttpdate(final OffsetDateTime dateTime) {
        return RFC_1123_DATE_TIME.format(dateTime.atZoneSameInstant(UTC));
    }

    @Override
    public HeaderStage ifNoneMatch(final String entityTag, final String... entityTags) {
        return ifNoneMatch(Lists.asList(entityTag, entityTags));
    }

    @Override
    public HeaderStage ifNoneMatch(final Collection<String> entityTags) {
        return header(IF_NONE_MATCH, join(", ", entityTags));
    }

    @Override
    public HeaderStage ifMatch(final String entityTag, final String... entityTags) {
        return ifMatch(Lists.asList(entityTag, entityTags));
    }

    @Override
    public HeaderStage ifMatch(final Collection<String> entityTags) {
        return header(IF_MATCH, join(", ", entityTags));
    }

    @Override
    public HeaderStage header(final String name, final String value) {
        return new Requester(this, query, ImmutableMultimap.<String, String>builder()
                .putAll(headers)
                .put(name, value)
                .build());
    }

    @Override
    public HeaderStage headers(final Multimap<String, String> headers) {
        return new Requester(this, query, ImmutableMultimap.<String, String>builder()
                .putAll(this.headers)
                .putAll(headers)
                .build());
    }

    @Override
    public CompletableFuture<ClientHttpResponse> call(final Route route) {
        return body(null).call(route);
    }

    @Override
    public <T> DispatchStage body(@Nullable final T body) {
        final ImmutableMultimap.Builder<String, String> builder = ImmutableMultimap.builder();
        headers.forEach(builder::putAll);

        return new ResponseDispatcher(arguments
                .withQueryParams(ImmutableMultimap.copyOf(query))
                .withRequestUri()
                .withHeaders(builder.build())
                .withBody(body)
        );
    }

    private final class ResponseDispatcher extends DispatchStage {

        private final RequestArguments arguments;

        ResponseDispatcher(final RequestArguments arguments) {
            this.arguments = arguments;
        }

        @Override
        public CompletableFuture<ClientHttpResponse> call(final Route route) {
            final RequestExecution before = plugin.beforeSend(this::send);
            final RequestExecution after = plugin.beforeDispatch(dispatch(before, route));

            // TODO get rid of this
            return throwingFunction(after::execute).apply(arguments);
        }

        private CompletableFuture<ClientHttpResponse> send(final RequestArguments arguments) {
            final CompletableFuture<ClientHttpResponse> future = new CompletableFuture<>();

            executor.execute(throwingRunnable(() -> {
                try {
                    final ClientHttpRequest request = createRequest(arguments);

                    writeHeaders(request, arguments);
                    writeBody(request, arguments);

                    future.complete(request.execute());
                } catch (final Exception e) {
                    future.completeExceptionally(e);
                }
            }));

            return future;
        }

        private void writeHeaders(final ClientHttpRequest request, final RequestArguments arguments) {
            arguments.getHeaders()
                    .forEach(request.getHeaders()::add);
        }

        private void writeBody(final ClientHttpRequest request, final RequestArguments arguments) throws IOException {
            worker.write(request, arguments);
        }

        private ClientHttpRequest createRequest(final RequestArguments arguments) throws IOException {
            final URI requestUri = arguments.getRequestUri();
            final HttpMethod method = arguments.getMethod();
            return requestFactory.createRequest(requestUri, method);
        }

        private RequestExecution dispatch(final RequestExecution execution, final Route route) {
            return arguments -> execution.execute(arguments).thenApply(dispatch(route));
        }

        private ThrowingUnaryOperator<ClientHttpResponse, Exception> dispatch(final Route route) {
            return response -> {
                try {
                    route.execute(response, worker);
                } catch (final NoWildcardException e) {
                    throw new UnexpectedResponseException(response);
                }

                return response;
            };
        }

    }

}
