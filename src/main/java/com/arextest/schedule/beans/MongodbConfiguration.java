package com.arextest.schedule.beans;

import com.arextest.common.utils.SerializationUtils;
import com.arextest.model.mock.Mocker;
import com.arextest.model.mock.Mocker.Target;
import com.mongodb.MongoClientSettings;
import lombok.extern.slf4j.Slf4j;
import org.bson.BsonReader;
import org.bson.BsonWriter;
import org.bson.codecs.Codec;
import org.bson.codecs.DecoderContext;
import org.bson.codecs.EncoderContext;
import org.bson.codecs.configuration.CodecRegistries;
import org.bson.codecs.configuration.CodecRegistry;
import org.bson.codecs.pojo.PojoCodecProvider;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.convert.converter.Converter;
import org.springframework.data.convert.CustomConversions;
import org.springframework.data.convert.ReadingConverter;
import org.springframework.data.convert.WritingConverter;
import org.springframework.data.mongodb.MongoDatabaseFactory;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.SimpleMongoClientDatabaseFactory;
import org.springframework.data.mongodb.core.convert.DbRefResolver;
import org.springframework.data.mongodb.core.convert.DefaultDbRefResolver;
import org.springframework.data.mongodb.core.convert.DefaultMongoTypeMapper;
import org.springframework.data.mongodb.core.convert.MappingMongoConverter;
import org.springframework.data.mongodb.core.convert.MongoCustomConversions;
import org.springframework.data.mongodb.core.mapping.MongoMappingContext;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;


@Slf4j
@Configuration(proxyBeanMethods = false)
public class MongodbConfiguration {
    @Value("${mongo.uri}")
    private String mongoUrl;


    @Bean
    @ConditionalOnMissingBean
    public MongoDatabaseFactory mongoDbFactory() {
        try {
            return new CompressionMongoClientDatabaseFactory(mongoUrl);
        } catch (Exception e) {
            LOGGER.error("cannot connect mongodb {}", e.getMessage(), e);
            throw e;
        }

    }

    @Bean
    @ConditionalOnMissingBean
    public MongoTemplate mongoTemplate(MongoDatabaseFactory mongoDatabaseFactory) {
        DbRefResolver dbRefResolver = new DefaultDbRefResolver(mongoDatabaseFactory);
        MappingMongoConverter converter = new MappingMongoConverter(dbRefResolver, new MongoMappingContext());

        converter.setCustomConversions(customConversions());
        converter.setTypeMapper(new DefaultMongoTypeMapper(null));
        converter.afterPropertiesSet();
        return new MongoTemplate(mongoDatabaseFactory, converter);
    }

    private MongoCustomConversions customConversions() {
        List<Converter<?, ?>> converters = new ArrayList<>();
        converters.add(new TypeReadMockerTargetConverter());
        converters.add(new TypeWriteMockerTargetConverter());
        return new MongoCustomConversions(converters);
    }

    private static class TypeWriteMockerTargetConverter implements Converter<String, Target> {
        @Override
        public Target convert(String source) {
            return SerializationUtils.useZstdDeserialize(source, Target.class);
        }
    }


    private static class TypeReadMockerTargetConverter implements Converter<Target, String> {
        @Override
        public String convert(Target source) {
            return SerializationUtils.useZstdSerializeToBase64(source);
        }
    }

    public static class CompressionMongoClientDatabaseFactory extends SimpleMongoClientDatabaseFactory {
        public CompressionMongoClientDatabaseFactory(String connectionString) {
            super(connectionString);
        }

        @Override
        public CodecRegistry getCodecRegistry() {
            CodecRegistry compressionCodecRegistry =
                    CodecRegistries.fromCodecs(new CompressionCodecImpl<>(Mocker.Target.class));
            final CodecRegistry customPojo = CodecRegistries.fromProviders(compressionCodecRegistry, PojoCodecProvider
                    .builder().automatic(true).build());
            return CodecRegistries.fromRegistries(MongoClientSettings.getDefaultCodecRegistry(),
                    customPojo);

        }

        private static final class CompressionCodecImpl<T> implements Codec<T> {
            private final Class<T> target;

            CompressionCodecImpl(Class<T> target) {
                this.target = target;
            }

            @Override
            public T decode(BsonReader reader, DecoderContext decoderContext) {
                return SerializationUtils.useZstdDeserialize(reader.readString(), this.target);
            }

            @Override
            public void encode(BsonWriter writer, T value, EncoderContext encoderContext) {
                String base64Result = SerializationUtils.useZstdSerializeToBase64(value);
                writer.writeString(base64Result);
            }

            @Override
            public Class<T> getEncoderClass() {
                return target;
            }
        }
    }
}