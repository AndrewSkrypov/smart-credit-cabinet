FROM swaggerapi/swagger-ui:v5.35.0@sha256:3e7305b17cb806a2925ed16145eae469a6084ab50d82fcec7ede600bba91650b

# The upstream image pins an older Alpine Brotli library.
RUN apk add --no-cache 'brotli-libs>=1.2.1-r0'
