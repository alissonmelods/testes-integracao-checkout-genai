package com.estudo.checkout.integracao;

import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.web.client.RestClient;

import java.net.http.HttpClient;
import java.time.Duration;

public final class FabricaRestClient {

    private FabricaRestClient() {
    }

    /**
     * Cria um RestClient com URL base e timeout de conexão e de leitura.
     * Usa o HttpClient do JDK porque o HttpURLConnection pode repetir um POST por conta própria em algumas falhas de
     * rede, o que atrapalharia o controle de tentativas feito pelos clients.
     */
    public static RestClient criar(RestClient.Builder builder, String urlBase, Duration timeout) {
        HttpClient httpClient = HttpClient.newBuilder()
                .version(HttpClient.Version.HTTP_1_1)
                .connectTimeout(timeout)
                .build();
        JdkClientHttpRequestFactory requestFactory = new JdkClientHttpRequestFactory(httpClient);
        requestFactory.setReadTimeout(timeout);
        return builder.baseUrl(urlBase).requestFactory(requestFactory).build();
    }
}
