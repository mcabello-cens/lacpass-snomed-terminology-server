# lacpass-snomed-terminology-server

**1. Requisitos**

- Servidor al menos 2 nucleos 
- Al menos 8 GB Ram
- Docker
- Docker-compose

Como mínimo Snowstorm debe tener 2G y Elasticsearch debe tener 4G de memoria para poder importar un Snapshot y realizar consultas ECL. Elasticsearch funcionará mejor con otros 4G de memoria libre en el servidor para el almacenamiento en caché de disco a nivel de sistema operativo.

Límites de memoria virtual de Docker

Debido a los límites de memoria virtual por defecto establecidos por los sistemas operativos que ahora es demasiado bajo para Elasticsearch y el contenedor Elasticsearch en este despliegue fallará.

En Ubuntu 20.04 en adelante, tendrá que ejecutar el siguiente comando antes de ejecutar docker-compose up :

```
sudo sysctl -w vm.max_map_count=262144
```

Si utiliza Windows y WSL2, deberá ejecutar el siguiente comando en Powershell:

```
wsl -d docker-desktop sysctl -w vm.max_map_count=262144
```


**2. Clonar este repositorio**

```
git clone https://github.com/mcabello-cens/lacpass-terminology-server.git
```

**3. Iniciar servidor Snowstorm**

```
cd snowstorm-7.12.0/
docker-compose up -d
```

**4. Importar SnomedIPS en servidor Snowstorm**

4.1 Acceder a interfaz Swagger desde http://<ip>:8080/swagger-ui/index.html#/ y ejecuta siguiente request

```
POST /imports
{
  "type": "SNAPSHOT",
  "branchPath": "MAIN",
  "createCodeSystemVersion": true
}
```

4.2 El ***request*** anterior una respuesta como la que sigue:

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

4.3 Copia el UUID generado en la respuesta anterior.

4.4 Desde la consola ejecutar. Reemplaza el UUID copiado en el ***request***
```
cd release-snomed-ips/

curl -X POST --header 'Content-Type: multipart/form-data' --header 'Accept: application/json' -F file=@SnomedCT_IPSTerminologyRelease_PRODUCTION_20221130T120000Z.zip 'http://localhost:8080/imports/c099c519-72c5-479f-a3d0-1ab46950ce03/archive'
```

**Notas:**

El proceso de importacion toma alrededor de 25 segundos.

SnomedIPS esta configurado para ser cargado en el branch MAIN. 

No es factible aún levantarlo en una rama distinta.


