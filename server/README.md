# HanView AI translation worker

Android APK 안에 OpenAI API 키를 넣지 않기 위한 서버 코드입니다.

- `OPENAI_API_KEY`: 필수 Worker secret
- `OPENAI_MODEL`: 선택. 기본값 `gpt-6-luna`

배포 후 Android 앱의 **AI 고급 번역 서버** 칸에 다음 형태의 주소를 저장합니다.

```
https://<your-worker-domain>/translate
```

중국 쇼핑앱 문맥에 맞춰 자연스럽게 번역하되 가격, 수량, 날짜, 크기, 무게, 배송/환불 조건은 바꾸지 않도록 설계했습니다.
