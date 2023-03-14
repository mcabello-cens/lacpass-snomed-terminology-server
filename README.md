# snowstorm-ips

**1. Clona este repositorio**

**2. Iniciar servidor Snowstorm**

```
cd snowstorm-7.12.0/
docker-compose up -d
```

**3. Importar SnomedIPS en servidor Snowstorm***

Acceder a interfaz Swagger desde http://<ip>:8080/swagger-ui/index.html#/ y ejecuta siguiente request

```
POST /imports
{
  "type": "SNAPSHOT",
  "branchPath": "MAIN",
  "createCodeSystemVersion": true
}
```

El ***request*** anterior una respuesta como la que sigue:

```
cache-control: no-cache,no-store,max-age=0,must-revalidate 
 connection: keep-alive 
 content-length: 0 
 date: Tue,14 Mar 2023 12:36:07 GMT 
 expires: 0 
 keep-alive: timeout=60 
 location: http://172.27.71.142:8080/imports/c099c519-72c5-479f-a3d0-1ab46950ce03 
 pragma: no-cache 
 x-content-type-options: nosniff 
 x-frame-options: DENY 
 x-xss-protection: 1; mode=block 
```

Copia el UUID generado en la respuesta anterior.

Desde la consola ejecutar
```
cd release-snomed-ips/

curl -X POST --header 'Content-Type: multipart/form-data' --header 'Accept: application/json' -F file=@SnomedCT_IPSTerminologyRelease_PRODUCTION_20221130T120000Z.zip 'http://localhost:8080/imports/c099c519-72c5-479f-a3d0-1ab46950ce03/archive'
```

**Notas:**
El proceso de importacion toma alrededor de 25 segundos.
SnomedIPS esta configurado para ser cargado en el branch MAIN. 
No es factible aún levantarlo en una rama distinta.


