<!DOCTYPE html>
<html lang="en">
<head>
<title>${file.name}文件预览</title>
    <meta charset="utf-8" />
    <meta name="viewport" content="width=device-width, user-scalable=yes, initial-scale=1.0">
    <#include "*/commonHeader.ftl">
   <script src="js/base64.min.js" type="text/javascript"></script>
   <script src="/heic/src/index.js" type="text/javascript"></script>
</head>
   <style>
     body {
            background-color: #404040;
        }
        .container {
            width: 100%;
            height: 100%;
        }
        .my-photo {
            max-width: 98%;
            margin:0 auto;
            border-radius:3px;
            box-shadow:rgba(0,0,0,0.15) 0 0 8px;
            background:#FBFBFB;
            border:1px solid #ddd;
            margin:1px auto;
            margin-left: 15px;
            padding:5px;
        }
    </style>
<body>
<div class="container">
    <#-- 获取反代配置 -->
    <#assign kkagentValue = kkagent>
    <#assign baseUrlValue = baseUrl.endsWith('/')?then(baseUrl, baseUrl + '/')>
    
    <#list imgUrls as img>
        <#-- 处理每个图片URL -->
        <#if img?contains("http://") || img?contains("https://")|| img?contains("ftp://")|| img?contains("file://")>
            <#assign originalUrl = img>
        <#else>
            <#assign originalUrl = baseUrl + img>
        </#if>
        
        <#-- 应用反代逻辑 -->
        <#assign finalUrl = originalUrl>
        <#if kkagentValue == "true">
            <#assign finalUrl = baseUrlValue + "getCorsFile?urlPath=" + originalUrl?url + "&key=${kkkey}">
        </#if>
        
        <img class="my-photo" src="${finalUrl}" data-original="${originalUrl}">
    </#list>
</div>
</body>
<script type="text/javascript">
    // 定义跨域处理方法
    function processImageUrls() {
        var kkagent = '${kkagent}';
        var baseUrl = '${baseUrl}'.endsWith('/') ? '${baseUrl}' : '${baseUrl}' + '/';
        var kkkey = '${kkkey}';
        
        // 获取所有图片
        var images = document.querySelectorAll('.my-photo');
        
        images.forEach(function(img) {
            var originalUrl = img.getAttribute('data-original');
            var currentSrc = img.src;
            
            // 检查是否需要反代
            if (kkagent === 'true') {
                // 构建反代URL
                var proxyUrl = baseUrl + 'getCorsFile?urlPath=' + encodeURIComponent(Base64.encode(originalUrl)) + "&key=" + kkkey;
                
                // 如果当前src不是反代URL，则更新
                if (currentSrc !== proxyUrl) {
                    img.src = proxyUrl;
                }
            }
        });
    }
    
    // 页面加载时处理图片URL
    document.addEventListener('DOMContentLoaded', function() {
        // 先处理跨域图片
        processImageUrls();
        
        // 然后设置HEIC转换监听器
        document.querySelectorAll('img').forEach(async x => {
            x.addEventListener('error', async function() {
                x.title = x.alt;
                x.src = await document.ConvertHeicToPng(x.src, stat => x.alt = stat);
            });
        });
    });
    
    /*初始化水印*/
    if (!!window.ActiveXObject || "ActiveXObject" in window) {
        // IE浏览器不添加水印
    } else {
        initWaterMark();
    }
</script>
</html>