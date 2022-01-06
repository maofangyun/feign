package example.mfy;

import feign.*;
import feign.codec.Decoder;
import feign.codec.Encoder;
import feign.codec.ErrorDecoder;
import feign.gson.GsonDecoder;
import feign.gson.GsonEncoder;

import java.io.IOException;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * @author maofangyun
 * @date 2021/12/1 17:04
 */
interface GitHub {

    public class Repository {
        String name;
    }

    public class Contributor {
        String login;
    }

    public class Issue {

        Issue() {

        }

        String title;
        String body;
        List<String> assignees;
        int milestone;
        List<String> labels;
    }

    @RequestLine("GET /users/{username}/repos?sort=full_name")
    List<Repository> repos(@Param("username") String owner);

    @RequestLine("GET /repos/{owner}/{repo}/contributors")
    List<Contributor> contributors(@Param("owner") String owner, @Param("repo") String repo);

    @RequestLine("POST /repos/{owner}/{repo}/issues")
    void createIssue(Issue issue, @Param("owner") String owner, @Param("repo") String repo);

    /** Lists all contributors for all repos owned by a user. */
    default List<String> contributors(String owner) {
        return repos(owner).stream()
                .flatMap(repo -> contributors(owner, repo.name).stream())
                .map(c -> c.login)
                .distinct()
                .collect(Collectors.toList());
    }

    static GitHub connect() {
        final Decoder decoder = new GsonDecoder();
        final Encoder encoder = new GsonEncoder();
        return Feign.builder()
                .encoder(encoder)
                .decoder(decoder)
                .errorDecoder(new GitHubErrorDecoder(decoder))
                .logger(new Logger.ErrorLogger())
                .logLevel(Logger.Level.BASIC)
                .requestInterceptor(template -> { template.header("Authorization", "token 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");})
                .options(new Request.Options(10, TimeUnit.SECONDS, 60, TimeUnit.SECONDS, true))
                .target(GitHub.class, "https://api.github.com");
    }

    static class GitHubErrorDecoder implements ErrorDecoder {

        final Decoder decoder;
        final ErrorDecoder defaultDecoder = new ErrorDecoder.Default();

        GitHubErrorDecoder(Decoder decoder) {
            this.decoder = decoder;
        }

        @Override
        public Exception decode(String methodKey, Response response) {
            try {
                // must replace status by 200 other GSONDecoder returns null
                response = response.toBuilder().status(200).build();
                return (Exception) decoder.decode(response, GitHubClientError.class);
            } catch (final IOException fallbackToDefault) {
                return defaultDecoder.decode(methodKey, response);
            }
        }
    }

    static class GitHubClientError extends RuntimeException {
        private String message; // parsed from json

        @Override
        public String getMessage() {
            return message;
        }
    }
}
