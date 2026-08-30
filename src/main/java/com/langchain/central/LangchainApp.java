package com.langchain.central;

import com.google.inject.Stage;
import com.langchain.central.module.CoreModule;
import com.langchain.central.module.ServiceModule;
import com.langchain.central.resource.LangChainResource;
import in.vectorpro.dropwizard.swagger.SwaggerBundle;
import in.vectorpro.dropwizard.swagger.SwaggerBundleConfiguration;
import io.dropwizard.Application;
import io.dropwizard.configuration.EnvironmentVariableSubstitutor;
import io.dropwizard.configuration.ResourceConfigurationSourceProvider;
import io.dropwizard.configuration.SubstitutingSourceProvider;
import io.dropwizard.forms.MultiPartBundle;
import io.dropwizard.setup.Bootstrap;
import io.dropwizard.setup.Environment;
import ru.vyarus.dropwizard.guice.GuiceBundle;
import ru.vyarus.dropwizard.guice.injector.lookup.InjectorLookup;

/**
 * @author ankush.nakaskar
 */
public class LangchainApp extends Application<BasicConfiguration> {

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

    }

    @Override
    public void initialize(final Bootstrap<BasicConfiguration> bootstrap) {

        bootstrap.setConfigurationSourceProvider(
                new SubstitutingSourceProvider(bootstrap.getConfigurationSourceProvider(),
                        new EnvironmentVariableSubstitutor()));

        bootstrap.addBundle(new MultiPartBundle());
        bootstrap.addBundle(swaggerBundle());
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
