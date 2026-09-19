package com.langchain.central;

import com.google.inject.Stage;
import com.langchain.central.module.CoreModule;
import com.langchain.central.module.ServiceModule;
import com.langchain.central.resource.LangChainResource;
import in.vectorpro.dropwizard.swagger.SwaggerBundle;
import in.vectorpro.dropwizard.swagger.SwaggerBundleConfiguration;
import io.dropwizard.Application;
import io.dropwizard.assets.AssetsBundle;
import io.dropwizard.configuration.EnvironmentVariableSubstitutor;
import io.dropwizard.configuration.ResourceConfigurationSourceProvider;
import io.dropwizard.configuration.SubstitutingSourceProvider;
import io.dropwizard.forms.MultiPartBundle;
import io.dropwizard.setup.Bootstrap;
import io.dropwizard.setup.Environment;
import java.io.IOException;
import java.util.EnumSet;
import javax.servlet.DispatcherType;
import javax.servlet.Filter;
import javax.servlet.FilterChain;
import javax.servlet.ServletException;
import javax.servlet.ServletRequest;
import javax.servlet.ServletResponse;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;
import org.eclipse.jetty.servlet.FilterHolder;
import ru.vyarus.dropwizard.guice.GuiceBundle;
import ru.vyarus.dropwizard.guice.injector.lookup.InjectorLookup;

/**
 * @author ankush.nakaskar
 */
public class LangchainApp extends Application<BasicConfiguration> {

    private static final String UI_PATH = "/ui";

    protected GuiceBundle guiceBundle;

    public static void main(final String[] args) throws Exception {
        new LangchainApp().run("server", "application.yml");
    }

    @Override
    public void run(final BasicConfiguration basicConfiguration,
                    final Environment environment) {

        final var injector = InjectorLookup.getInjector(this)
                .orElseThrow(() -> new IllegalStateException("Guice injector is not available"));
        environment.jersey().register(injector.getInstance(LangChainResource.class));

        redirectRootToUi(environment);
    }


    private void redirectRootToUi(final Environment environment) {
        environment.getApplicationContext().addFilter(new FilterHolder(new Filter() {
            @Override
            public void doFilter(final ServletRequest request,
                                 final ServletResponse response,
                                 final FilterChain chain) throws IOException, ServletException {

                final var httpRequest = (HttpServletRequest) request;
                final String uri = httpRequest.getRequestURI();

                if ("/".equals(uri) || UI_PATH.equals(uri)) {
                    ((HttpServletResponse) response)
                            .sendRedirect(httpRequest.getContextPath() + UI_PATH + "/");
                    return;
                }
                chain.doFilter(request, response);
            }
        }), "/*", EnumSet.of(DispatcherType.REQUEST));
    }

    @Override
    public void initialize(final Bootstrap<BasicConfiguration> bootstrap) {

        bootstrap.setConfigurationSourceProvider(
                new SubstitutingSourceProvider(bootstrap.getConfigurationSourceProvider(),
                        new EnvironmentVariableSubstitutor()));

        bootstrap.addBundle(new MultiPartBundle());
        bootstrap.addBundle(swaggerBundle());
        bootstrap.addBundle(new AssetsBundle("/assets", UI_PATH, "index.html", "ui"));
        bootstrap.setConfigurationSourceProvider(new ResourceConfigurationSourceProvider());


        guiceBundle = guiceBundle(bootstrap);
        bootstrap.addBundle(guiceBundle);
        super.initialize(bootstrap);
    }



    SwaggerBundle<BasicConfiguration> swaggerBundle() {
        return new SwaggerBundle<BasicConfiguration>() {
            @Override
            protected SwaggerBundleConfiguration getSwaggerBundleConfiguration(BasicConfiguration configuration) {
                return configuration.getSwagger();
            }
        };
    }

    GuiceBundle guiceBundle(Bootstrap<BasicConfiguration> bootstrap) {

        return GuiceBundle.builder()
                .enableAutoConfig(getClass().getPackage()
                        .getName())
                .modules(new ServiceModule(bootstrap.getObjectMapper(), bootstrap.getMetricRegistry()),  new CoreModule())
                .build(Stage.PRODUCTION);
    }


}
