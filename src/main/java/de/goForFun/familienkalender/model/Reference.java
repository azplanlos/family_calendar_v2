package de.goForFun.familienkalender.model;

import org.mapstruct.Qualifier;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

@Retention(RetentionPolicy.CLASS)
@Qualifier // make sure that this is the MapStruct qualifier annotation
@Target(ElementType.METHOD)
public @interface Reference {
}
