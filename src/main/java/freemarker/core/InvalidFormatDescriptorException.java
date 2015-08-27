/*
 * Copyright 2014 Attila Szegedi, Daniel Dekany, Jonathan Revusky
 * 
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 * 
 * http://www.apache.org/licenses/LICENSE-2.0
 * 
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package freemarker.core;

/**
 * Used when creating {@link TemplateDateFormat}-s and {@link TemplateNumberFormat}-s to indicate that the format
 * descriptor string (often some kind of pattern) is malformed.
 * 
 * @since 2.3.24
 */
public class InvalidFormatDescriptorException extends Exception {
    
    private final String formatDescriptor;

    public InvalidFormatDescriptorException(String message, String formatDescriptor, Throwable cause) {
        super(message, cause);
        this.formatDescriptor = formatDescriptor;
    }

    public InvalidFormatDescriptorException(String message, String formatDescriptor) {
        this(message, formatDescriptor, null);
    }

    
    public String getFormatDescriptor() {
        return formatDescriptor;
    }

}
